package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity

/** 自动删除后会话行预览的纯规则（AUTO_DELETE_DESIGN §3.2；口径同服务端 `lastVisible`）。 */
internal object AutoDeletePreview {
    /** 清理后剩余消息（新→旧）里的「最后一条可见」：跳过 msg_op 事件行与已删墓碑；撤回行是合法预览（显示「撤回了一条消息」）。 */
    fun pick(newestFirst: List<MessageEntity>): MessageEntity? =
        newestFirst.firstOrNull { it.contentType != "msg_op" && it.deletedAt == null }
}

/**
 * 会话行预览此刻是否需要按本地库重算（v2：过期集合是窗口 `[from, now-sec*1000]`，**不是前缀**）：
 * - 预览落在窗口里（已过期，清理例程要把它换成「最后一条未过期的可见消息」，可越过窗口取 `from` 之前的）；
 * - 预览是空的但会话有时间（服务端过滤成空 / [keepAutoDeleteTail] 刚清掉）：窗口之前可能还有可见消息，要能补回来。
 * 重算结果与现状相同就不写库（见 `rewritePreview`），所以前台每 30 秒空转不会反复触发会话列表 Flow。
 */
internal fun AutoDeletePreview.stale(conv: ConversationEntity, now: Long): Boolean {
    if (conv.autoDeleteSec <= 0 || conv.lastTimestamp <= 0) return false
    if (AutoDelete.expired(conv.lastTimestamp, conv.autoDeleteSec, conv.autoDeleteFrom, now)) return true
    return conv.lastContent.isEmpty() && conv.lastSysEvent.isEmpty()
}

/**
 * 服务端会话快照落库时的自动删除收口（接在 [keepNewerLocalTail] 之后）：
 * - 服务端 `last_message` 被它过滤成空 → 快照的 `lastTimestamp` 可能是 0，会让这一行沉到列表底；
 *   排序时间沿用本地值（只增不减）。
 * - 快照预览按**本机时钟**已落进过期窗口（两端时钟差 / 快照在途时过期）→ 当场清空预览，不先闪旧预览
 *   再等下一次扫描；`lastTimestamp` / `lastConvSeq` 保留（排序）。**未读不动**：窗口之前（`from` 之前）的
 *   未读仍然有效，由 `purgeExpired` 按实际清掉的行扣减，服务端下次列表再校准。
 */
internal fun ConversationEntity.keepAutoDeleteTail(local: ConversationEntity?, now: Long): ConversationEntity {
    if (autoDeleteSec <= 0) return this
    if (lastTimestamp > 0 && !AutoDelete.expired(lastTimestamp, autoDeleteSec, autoDeleteFrom, now)) return this
    return copy(
        lastTimestamp = maxOf(lastTimestamp, local?.lastTimestamp ?: 0L),
        lastContent = "", lastContentType = "text", lastFrom = "", lastFromNickname = "", lastRecalled = false,
        lastSysEvent = "", lastSysArgs = "", lastSysSegments = "",
    )
}
