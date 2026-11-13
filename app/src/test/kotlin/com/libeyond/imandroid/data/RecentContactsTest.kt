package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「最近联系」取数判据（ORG_DIRECTORY_DESIGN §7.1），三端同一套。 */
class RecentContactsTest {

    private val me = "1000000001"

    private fun conv(
        id: String,
        ts: Long,
        peer: String = "peer_$id",
        isGroup: Boolean = false,
        peerDeleted: Boolean = false,
        pinnedAt: Long = 0,
        unread: Int = 0,
        muted: Boolean = false,
        title: String = "",
        peerRemark: String = "",
    ) = ConversationEntity(
        ownerUid = me, convId = id, isGroup = isGroup, peerUid = if (isGroup) "" else peer,
        title = title, peerRemark = peerRemark, peerDeleted = peerDeleted, lastTimestamp = ts,
        pinnedAt = pinnedAt, unread = unread, muted = muted,
    )

    private fun ids(list: List<ConversationEntity>) = list.map { it.convId }

    @Test
    fun `按最后一条消息时间倒序`() {
        val got = RecentContacts.pick(listOf(conv("a", 100), conv("b", 300), conv("c", 200)), me)
        assertEquals(listOf("b", "c", "a"), ids(got))
    }

    @Test
    fun `时间相同按会话 ID 升序`() {
        val got = RecentContacts.pick(listOf(conv("u_3", 500), conv("u_1", 500), conv("u_2", 500), conv("u_0", 400)), me)
        assertEquals(listOf("u_1", "u_2", "u_3", "u_0"), ids(got))
    }

    @Test
    fun `只取前 5 个`() {
        val got = RecentContacts.pick((1..8).map { conv("c$it", it.toLong()) }, me)
        assertEquals(listOf("c8", "c7", "c6", "c5", "c4"), ids(got))
    }

    @Test
    fun `不足 5 个有几个给几个，一个都没有给空表`() {
        assertEquals(listOf("a"), ids(RecentContacts.pick(listOf(conv("a", 1)), me)))
        assertTrue(RecentContacts.pick(emptyList(), me).isEmpty())
    }

    @Test
    fun `群聊不算`() {
        val got = RecentContacts.pick(listOf(conv("g_1", 900, isGroup = true), conv("a", 100)), me)
        assertEquals(listOf("a"), ids(got))
    }

    @Test
    fun `排除和自己的会话`() {
        val got = RecentContacts.pick(listOf(conv("self", 900, peer = me), conv("a", 100)), me)
        assertEquals(listOf("a"), ids(got))
    }

    @Test
    fun `排除系统通知会话`() {
        val got = RecentContacts.pick(listOf(conv("sys", 900, peer = DetailActions.SYSTEM_UID), conv("a", 100)), me)
        assertEquals(listOf("a"), ids(got))
    }

    @Test
    fun `排除对方已注销的会话`() {
        val got = RecentContacts.pick(listOf(conv("gone", 900, peerDeleted = true), conv("a", 100)), me)
        assertEquals(listOf("a"), ids(got))
    }

    @Test
    fun `排除没有任何消息的会话`() {
        val got = RecentContacts.pick(listOf(conv("empty", 0), conv("a", 100)), me)
        assertEquals(listOf("a"), ids(got))
    }

    @Test
    fun `不看置顶 未读 免打扰`() {
        val got = RecentContacts.pick(
            listOf(
                conv("old_pinned", 100, pinnedAt = 9_999, unread = 7),
                conv("new_muted", 300, muted = true),
                conv("mid", 200),
            ),
            me,
        )
        assertEquals(listOf("new_muted", "mid", "old_pinned"), ids(got))
    }

    @Test
    fun `被排除的会话不占名额`() {
        val convs = (1..5).map { conv("c$it", it.toLong()) } +
            listOf(conv("g", 100, isGroup = true), conv("sys", 99, peer = DetailActions.SYSTEM_UID))
        assertEquals(listOf("c5", "c4", "c3", "c2", "c1"), ids(RecentContacts.pick(convs, me)))
    }

    @Test
    fun `显示名 备注优先于昵称`() {
        assertEquals("老王", RecentContacts.nameOf(conv("a", 1, title = "Wang", peerRemark = "老王")))
        assertEquals("Wang", RecentContacts.nameOf(conv("a", 1, title = "Wang")))
        assertEquals("", RecentContacts.nameOf(conv("a", 1)))
    }
}
