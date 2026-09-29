package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity

/**
 * 通知设置「例外」列表（NOTIFICATIONS_DESIGN §3.5）：本机会话表里**有效**免打扰的会话
 * （[MuteState.isMutedNow]，第二批起过期的定时免打扰要自动从列表消失，NOTIFICATIONS_P1_DESIGN §4.4），
 * 按类型（私聊 / 群聊）过滤，按最后消息时间倒序。取消免打扰复用现有
 * `PUT /conversations/{id}/settings`（见 `ui/NotificationSettingsHost.kt`）。
 *
 * @param nowMs 判「是否免打扰」的当前时刻，定时免打扰到期刷新用。
 */
object NotificationExceptions {
    fun of(conversations: List<ConversationEntity>, group: Boolean, nowMs: Long = System.currentTimeMillis()): List<ConversationEntity> =
        conversations
            .filter { MuteState.isMutedNow(it.muted, it.muteUntil, nowMs) && it.isGroup == group }
            .sortedByDescending { it.lastTimestamp }
}
