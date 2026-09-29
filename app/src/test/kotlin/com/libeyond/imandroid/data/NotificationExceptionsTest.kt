package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 通知设置「例外」列表：按类型过滤 muted 会话，按最后消息时间倒序（NOTIFICATIONS_DESIGN §3.5）。 */
class NotificationExceptionsTest {

    private fun conv(id: String, group: Boolean, muted: Boolean, ts: Long) = ConversationEntity(
        ownerUid = "me", convId = id, isGroup = group, muted = muted, lastTimestamp = ts,
    )

    @Test
    fun `只挑 muted 且类型匹配的会话`() {
        val list = listOf(
            conv("a", group = false, muted = true, ts = 1),
            conv("b", group = false, muted = false, ts = 2),
            conv("c", group = true, muted = true, ts = 3),
        )
        assertEquals(listOf("a"), NotificationExceptions.of(list, group = false).map { it.convId })
        assertEquals(listOf("c"), NotificationExceptions.of(list, group = true).map { it.convId })
    }

    @Test
    fun `按最后消息时间倒序`() {
        val list = listOf(
            conv("old", group = false, muted = true, ts = 100),
            conv("new", group = false, muted = true, ts = 300),
            conv("mid", group = false, muted = true, ts = 200),
        )
        assertEquals(listOf("new", "mid", "old"), NotificationExceptions.of(list, group = false).map { it.convId })
    }

    @Test
    fun `没有免打扰会话时是空列表`() {
        assertEquals(emptyList<ConversationEntity>(), NotificationExceptions.of(emptyList(), group = false))
    }

    // ——— 定时免打扰到期（NOTIFICATIONS_P1_DESIGN §4.3/§4.4）———

    private fun timedConv(id: String, muteUntil: Long) = ConversationEntity(
        ownerUid = "me", convId = id, isGroup = false, muted = true, muteUntil = muteUntil,
    )

    @Test
    fun `过期的定时免打扰会话到点自动从例外列表消失`() {
        val list = listOf(timedConv("expired", muteUntil = 500), timedConv("active", muteUntil = 2000))
        assertEquals(listOf("active"), NotificationExceptions.of(list, group = false, nowMs = 1000).map { it.convId })
    }
}
