package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity

/**
 * 通讯录首页「最近联系」的取数（`../IMServer/docs/design/ORG_DIRECTORY_DESIGN.md` §7.1）。
 *
 * 三端同一套判据（iOS `IMRecentContacts`、Web `recentContacts.ts`），输入是内存里已有的会话列表，
 * **不查消息库、不拉接口**——会话列表变了（收发消息、删会话）就跟着重算。
 *
 * 回答的问题是「我最近在跟谁说话」，所以：
 * - 只要单聊（群已有「群聊」入口；含群的话这一段就等于消息页前 5 条，重复）；
 * - 按最后一条消息时间排，**不看置顶、不看未读、不看免打扰**。
 */
object RecentContacts {

    /** 最多显示几个；不足有几个显示几个，一个都没有时整段不出现（由界面判空）。 */
    const val LIMIT = 5

    /**
     * @param convs 会话列表（顺序无所谓，这里自己排）
     * @param myUid 当前登录用户；用来排除「和自己的会话」
     */
    fun pick(convs: List<ConversationEntity>, myUid: String, limit: Int = LIMIT): List<ConversationEntity> =
        convs.asSequence()
            .filter { isCandidate(it, myUid) }
            // 时间倒序；时间相同按会话 ID 升序——三端顺序要一致，不能靠各端列表的原始顺序
            .sortedWith(compareByDescending<ConversationEntity> { it.lastTimestamp }.thenBy { it.convId })
            .take(limit.coerceAtLeast(0))
            .toList()

    /**
     * 行上显示的名字：备注 > 昵称（`title` 入库时已按 [DisplayName.ofConversation] 解析过）。
     * 可能为空——末级「未命名用户」文案要读资源，由界面兜底，这里保持不碰 android。
     */
    fun nameOf(c: ConversationEntity): String = c.peerRemark.ifBlank { c.title }

    private fun isCandidate(c: ConversationEntity, myUid: String): Boolean =
        !c.isGroup &&
            c.peerUid != myUid &&                       // 和自己的会话
            !DetailActions.isSystemPeer(c.peerUid) &&   // 系统通知会话
            !c.peerDeleted &&                           // 对方已注销
            c.lastTimestamp > 0                         // 没有任何消息（无 last message 时时间为 0）
}
