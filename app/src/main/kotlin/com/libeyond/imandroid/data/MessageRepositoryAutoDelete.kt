package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.AutoDeleteFloor
import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.MessageData

/**
 * 自动删除消息的库侧动作（AUTO_DELETE_DESIGN §3.1/§3.2）。写成扩展函数只为控 `MessageRepository.kt` 的体量。
 * 口径只有一处：[AutoDelete]；**不调服务端 hide 接口**（那会往服务端写 hidden 行、与「不动服务端」冲突），
 * 和「仅为我删除」一样只是物理移除本机行（会话页订阅 Room，气泡随之就地消失）。
 */

/**
 * 入库过滤：这条消息按所属会话的档位已过期，就**不落库**（新设备首次同步一个 1 天档的老会话，不闪旧消息）。
 * 但它仍是「服务端给过了」：区间登记 / 同步游标照推，否则会被判成缺口反复重拉。回 `true` = 已丢弃。
 */
internal suspend fun MessageRepository.droppedAsExpired(owner: String, m: MessageData): Boolean {
    val conv = conversations.byId(owner, m.convId)
    if (!AutoDelete.expired(m.timestamp, conv?.autoDeleteSec ?: 0, conv?.autoDeleteFrom ?: 0L, System.currentTimeMillis())) return false
    if (m.convSeq > 0) tx.run {
        ranges.register(owner, m.convId, m.convSeq, m.convSeq)
        advanceSyncedIfNext(owner, m.convId, m.convSeq)
    }
    log.i("auto_delete_dropped_on_arrival", "convId" to m.convId, "seq" to m.convSeq)
    return true
}

/**
 * conv_update(settings) 落库：五列设置**整体覆盖**（`auto_delete_sec` 与 `auto_delete_from` 成对，会话级共享）。
 * 回 `true` = 档位或起点变了且新档位>0，调用方扫该会话（[AutoDelete.needsSweep]）。
 */
internal suspend fun MessageRepository.applySettingsUpdate(owner: String, u: ConvUpdateData): Boolean {
    val old = conversations.byId(owner, u.convId)
    val sec = AutoDelete.effectiveSec(u.autoDeleteSec, u.autoDeleteFrom)
    val sweep = AutoDelete.needsSweep(old?.autoDeleteSec ?: 0, old?.autoDeleteFrom ?: 0L, sec, u.autoDeleteFrom)
    conversations.updateSettings(owner, u.convId, u.pinnedAt, u.muted, u.muteUntil, u.markedUnread, sec, u.autoDeleteFrom)
    AutoDeleteFloor.set(u.convId, sec, u.autoDeleteFrom)
    return sweep
}

/**
 * 清掉一个会话里已过期的本机消息（窗口 `[from, now-sec*1000]`，`from` 之前的永不动），并就地重算会话行的
 * 预览 / 未读（会话行**保留**）。幂等：窗口里没有消息、预览也不用换就什么都不动。返回被移除的 conv_seq
 * （给置顶横幅 / 面板对齐用）。
 *
 * 预览：落在窗口里（或已被清空）→ 取剩余的最后一条可见消息（可越过窗口取 `from` 之前的），没有则空预览；
 * 未读：减去被清掉的未读条数（服务端下次列表按自己的时钟再校准）。
 */
suspend fun MessageRepository.purgeExpired(owner: String, convId: String, now: Long): List<Long> {
    val conv = conversations.byId(owner, convId) ?: return emptyList()
    if (AutoDelete.window(conv.autoDeleteSec, conv.autoDeleteFrom, now) == null) return emptyList()
    var seqs: List<Long> = emptyList()
    tx.run {
        // 会话行在事务里重读：事务外读到的那份可能已被并发到达的新消息改过（预览 / 未读），拿旧值整列回写会盖掉它
        val cur = conversations.byId(owner, convId) ?: return@run
        val win = AutoDelete.window(cur.autoDeleteSec, cur.autoDeleteFrom, now) ?: return@run
        seqs = messages.expiredSeqs(owner, convId, win.first, win.last)
        val stale = AutoDeletePreview.stale(cur, now)
        if (seqs.isEmpty() && !stale) return@run
        val unreadGone = if (seqs.isEmpty()) 0 else messages.countExpiredUnread(owner, convId, win.first, win.last, cur.readSeq)
        if (seqs.isNotEmpty()) messages.deleteExpired(owner, convId, win.first, win.last)
        rewritePreview(owner, cur, stale, unreadGone)
    }
    if (seqs.isNotEmpty()) log.i("auto_delete_purged", "convId" to convId, "count" to seqs.size)
    return seqs
}

private suspend fun MessageRepository.rewritePreview(owner: String, conv: ConversationEntity, recompute: Boolean, unreadGone: Int) {
    val unread = maxOf(0, conv.unread - unreadGone)
    val mention = conv.mentionUnread && unread > 0
    val last = if (recompute) AutoDeletePreview.pick(messages.latestWindow(owner, conv.convId, PREVIEW_SCAN)) else null
    val next = if (!recompute) conv else conv.copy(
        lastContent = last?.let { MessagePreview.of(it.contentType, it.content, it.caption, viewerIsSender = it.sender == owner) }.orEmpty(),
        lastContentType = last?.contentType ?: "text",
        lastFrom = last?.sender.orEmpty(),
        lastFromNickname = last?.fromNickname.orEmpty(),
        lastRecalled = last?.recalledAt != null,
        lastSysEvent = last?.sysEvent.orEmpty(),
        lastSysArgs = last?.sysArgs.orEmpty(),
        lastSysSegments = last?.sysSegments.orEmpty(),
    )
    if (next == conv && unread == conv.unread && mention == conv.mentionUnread) return // 没变不写：别让会话列表 Flow 白重发
    // lastTimestamp / lastConvSeq 保留：列表按它排序，置 0 会让这一行沉底，看起来像被删了（同 clearConversation）
    conversations.updatePreview(
        owner, conv.convId,
        content = next.lastContent, type = next.lastContentType, from = next.lastFrom, nick = next.lastFromNickname,
        recalled = next.lastRecalled, sysEvent = next.lastSysEvent, sysArgs = next.lastSysArgs, sysSegments = next.lastSysSegments,
        unread = unread, mention = mention,
    )
}

/** 重算预览时向前看的行数：够越过连续的 msg_op 事件行 / 墓碑。 */
private const val PREVIEW_SCAN = 20
