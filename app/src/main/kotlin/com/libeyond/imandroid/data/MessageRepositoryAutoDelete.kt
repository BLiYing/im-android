package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
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
    val sec = conversations.byId(owner, m.convId)?.autoDeleteSec ?: 0
    if (!AutoDelete.expired(m.timestamp, sec, System.currentTimeMillis())) return false
    if (m.convSeq > 0) tx.run {
        ranges.register(owner, m.convId, m.convSeq, m.convSeq)
        advanceSyncedIfNext(owner, m.convId, m.convSeq)
    }
    log.i("auto_delete_dropped_on_arrival", "convId" to m.convId, "seq" to m.convSeq)
    return true
}

/**
 * 清掉一个会话里已过期的本机消息，并就地重算会话行的预览 / 未读（会话行**保留**）。
 * 幂等：没有过期的就什么都不动。返回被移除的 conv_seq（给置顶横幅 / 面板对齐用）。
 *
 * 预览：最后一条已过期 → 取剩余的最后一条可见消息，没有则空预览；未读：最后一条已过期 = 整个会话都过期 → 0，
 * 否则减去被清掉的未读条数（服务端下次列表按自己的时钟再校准）。
 */
suspend fun MessageRepository.purgeExpired(owner: String, convId: String, now: Long): List<Long> {
    val conv = conversations.byId(owner, convId) ?: return emptyList()
    val floor = AutoDelete.floorTs(conv.autoDeleteSec, now)
    if (floor <= 0L) return emptyList()
    var seqs: List<Long> = emptyList()
    tx.run {
        // 会话行在事务里重读：事务外读到的那份可能已被并发到达的新消息改过（预览 / 未读），拿旧值整列回写会盖掉它
        val cur = conversations.byId(owner, convId) ?: return@run
        seqs = messages.expiredSeqs(owner, convId, floor)
        val previewGone = cur.lastTimestamp in 1..floor
        // lastTimestamp 刻意保留（排序），所以 previewGone 清空后恒为真；预览已是空的就别每 30 秒空写一遍（会白白触发会话列表 Flow 重发）
        val previewDirty = previewGone && (
            cur.lastContent.isNotEmpty() || cur.lastSysEvent.isNotEmpty() || cur.lastFrom.isNotEmpty() ||
                cur.lastRecalled || cur.unread > 0 || cur.mentionUnread
            )
        if (seqs.isEmpty() && !previewDirty) return@run
        val unreadGone = if (seqs.isEmpty()) 0 else messages.countExpiredUnread(owner, convId, floor, cur.readSeq)
        if (seqs.isNotEmpty()) messages.deleteExpired(owner, convId, floor)
        rewritePreview(owner, cur, previewGone, unreadGone)
    }
    if (seqs.isNotEmpty()) log.i("auto_delete_purged", "convId" to convId, "count" to seqs.size)
    return seqs
}

private suspend fun MessageRepository.rewritePreview(owner: String, conv: ConversationEntity, previewGone: Boolean, unreadGone: Int) {
    val unread = if (previewGone) 0 else maxOf(0, conv.unread - unreadGone)
    val mention = conv.mentionUnread && unread > 0
    val last = if (previewGone) messages.latestWindow(owner, conv.convId, 1).firstOrNull() else null
    if (!previewGone) {
        conversations.updatePreview(
            owner, conv.convId, conv.lastContent, conv.lastContentType, conv.lastFrom, conv.lastFromNickname, conv.lastRecalled,
            conv.lastSysEvent, conv.lastSysArgs, conv.lastSysSegments, unread, mention,
        )
        return
    }
    // lastTimestamp / lastConvSeq 保留：列表按它排序，置 0 会让这一行沉底，看起来像被删了（同 clearConversation）
    conversations.updatePreview(
        owner, conv.convId,
        content = last?.let { MessagePreview.of(it.contentType, it.content, it.caption, viewerIsSender = it.sender == owner) }.orEmpty(),
        type = last?.contentType ?: "text",
        from = last?.sender.orEmpty(),
        nick = last?.fromNickname.orEmpty(),
        recalled = last?.recalledAt != null,
        sysEvent = last?.sysEvent.orEmpty(),
        sysArgs = last?.sysArgs.orEmpty(),
        sysSegments = last?.sysSegments.orEmpty(),
        unread = unread,
        mention = mention,
    )
}
