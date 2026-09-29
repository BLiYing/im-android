package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity

/**
 * 通知设置「例外」列表（NOTIFICATIONS_DESIGN §3.5）：本机会话表里 `muted=true` 的会话，
 * 按类型（私聊 / 群聊）过滤，按最后消息时间倒序。**不新做免打扰**——数据就是现有会话表的
 * `muted` 标志，取消免打扰复用现有 `PUT /conversations/{id}/settings`（见 `ui/NotificationSettingsHost.kt`）。
 */
object NotificationExceptions {
    fun of(conversations: List<ConversationEntity>, group: Boolean): List<ConversationEntity> =
        conversations
            .filter { it.muted && it.isGroup == group }
            .sortedByDescending { it.lastTimestamp }
}
