package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.FriendEntry

/**
 * 首页全局搜索（会话 / 联系人 / 聊天记录）的命中口径，对齐 iOS `IMGlobalSearchViewController`
 * 的 `recomputeForKeyword:` 与 Web 全局搜索：
 *  - 会话/群：标题子串命中；联系人：备注/昵称/用户名/uid 命中；
 *  - 聊天记录：**不聚合**，一条命中一行（同会话可出现多行）；只保留当前会话列表里还在的会话
 *    （本地库可能残留已退群/已删会话的历史消息，它们没有可打开的落点）；时间倒序。
 */
object GlobalSearch {

    data class RecordHit(val conv: ConversationEntity, val msg: MessageEntity, val snippet: String)

    fun convHits(convs: List<ConversationEntity>, keyword: String, titleOf: (ConversationEntity) -> String): List<ConversationEntity> {
        val q = ListSearch.normalizedQuery(keyword)
        if (q.isEmpty()) return emptyList()
        return convs.filter { titleOf(it).contains(q, ignoreCase = true) }
    }

    /** 只在已是好友（accepted）的人里找；待确认/已申请的不算「联系人」。 */
    fun friendHits(friends: Collection<FriendEntry>, keyword: String): List<FriendEntry> {
        val q = ListSearch.normalizedQuery(keyword)
        if (q.isEmpty()) return emptyList()
        return friends.filter {
            it.status == FriendEntry.ACCEPTED &&
                ListSearch.matches(q, listOf(it.displayName, it.remark, it.nickname, it.username, it.userId))
        }
    }

    fun recordHits(msgs: List<MessageEntity>, convs: List<ConversationEntity>, keyword: String): List<RecordHit> {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val byId = convs.associateBy { it.convId }
        return msgs.mapNotNull { m ->
            val conv = byId[m.convId] ?: return@mapNotNull null
            RecordHit(conv, m, snippet(m, needle))
        }
    }

    /**
     * 摘要 = **真正含 needle 的字段**（needle 已 lowercase）：caption > 文本正文 > 文件名；
     * 否则文件名命中却显 caption，副行没有高亮、像误命中（iOS 2026-08-21 code-review #3 同款）。
     */
    fun snippet(m: MessageEntity, needle: String): String {
        val isText = m.contentType.isEmpty() || m.contentType == "text"
        val caption = m.caption.orEmpty()
        val fileName = m.fileName.orEmpty()
        if (caption.isNotEmpty() && caption.lowercase().contains(needle)) return caption
        if (isText && m.content.lowercase().contains(needle)) return m.content
        if (fileName.isNotEmpty() && fileName.lowercase().contains(needle)) return fileName
        return caption.ifEmpty { fileName.ifEmpty { m.content } }
    }
}
