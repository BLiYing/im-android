package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「消息」Tab 蓝点计数：与 im-web `badgeCountOf`（desktopNotify.test.ts）、iOS `IMTabUnreadCount` 同一组用例。 */
class TabUnreadTest {

    private fun conv(id: String, unread: Int = 0, muted: Boolean = false, mention: Boolean = false, marked: Boolean = false) =
        ConversationEntity(
            ownerUid = "me", convId = id, unread = unread,
            muted = muted, mentionUnread = mention, markedUnread = marked,
        )

    @Test
    fun `没有会话或全部已读为 0`() {
        assertEquals(0, TabUnread.count(emptyList()))
        assertEquals(0, TabUnread.count(listOf(conv("a"), conv("b"))))
    }

    @Test
    fun `未免打扰的会话按条数累加`() {
        assertEquals(7, TabUnread.count(listOf(conv("a", unread = 3), conv("b", unread = 4))))
    }

    @Test
    fun `免打扰的会话不计`() {
        assertEquals(2, TabUnread.count(listOf(conv("a", unread = 2), conv("m", unread = 99, muted = true))))
    }

    @Test
    fun `免打扰里被 @ 只记 1——穿透免打扰但不放大成条数`() {
        assertEquals(1, TabUnread.count(listOf(conv("m", unread = 40, muted = true, mention = true))))
    }

    @Test
    fun `手动标为未读不点亮 Tab`() {
        assertEquals(0, TabUnread.count(listOf(conv("a", marked = true))))
    }
}
