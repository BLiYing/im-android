package com.libeyond.imandroid.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通话界面读 IM 自己的数据：单聊读会话行，群通话按群成员表，顺序与 IM 群聊一致；IM 里没有名字才返回 null（走兜底）。 */
class RtcProfileSourcesTest {

    private fun peer(uid: String, title: String = "", avatar: String = "", remark: String = "") =
        RtcProfileSources.PeerRow(uid, title, avatar, remark)

    private fun member(uid: String, groupNick: String = "", nick: String = "", username: String = "", avatar: String = "") =
        RtcProfileSources.MemberRow(uid, groupNick, nick, username, avatar)

    @Test
    fun one_to_one_reads_conversation_title_and_avatar() {
        val s = RtcProfileSources()
        s.setPeers(listOf(peer("u1", "老王", "/a.png")))
        assertEquals("老王", s.name("u1", ""))
        assertEquals("/a.png", s.avatarUrl("u1", ""))
    }

    @Test
    fun unknown_uid_or_unnamed_placeholder_is_a_miss() {
        val s = RtcProfileSources()
        s.setPeers(listOf(peer("u1", "未命名用户")))
        assertNull(s.name("u1", ""))
        assertNull(s.name("nobody", ""))
        assertNull(s.avatarUrl("nobody", ""))
    }

    @Test
    fun group_call_prefers_remark_then_group_nickname_then_nickname_then_handle() {
        val s = RtcProfileSources()
        s.putMembers("g1", listOf(member("u1", groupNick = "群里的小明", nick = "小明", username = "xm")))
        assertEquals("群里的小明", s.name("u1", "g1"))
        s.putMembers("g1", listOf(member("u1", nick = "小明", username = "xm")))
        assertEquals("小明", s.name("u1", "g1"))
        s.putMembers("g1", listOf(member("u1", username = "xm")))
        assertEquals("@xm", s.name("u1", "g1"))
        s.setPeers(listOf(peer("u1", "小明(会话)", remark = "老王")))
        assertEquals("老王", s.name("u1", "g1"))
    }

    @Test
    fun group_member_of_another_group_is_not_used() {
        val s = RtcProfileSources()
        s.putMembers("g1", listOf(member("u1", groupNick = "群里的小明")))
        assertNull("单聊不读群昵称", s.name("u1", ""))
        assertNull("别的群的成员表不串", s.name("u1", "g2"))
    }

    @Test
    fun group_call_falls_back_to_conversation_title_and_avatar() {
        val s = RtcProfileSources()
        s.setPeers(listOf(peer("u1", "小明", "/c.png")))
        assertEquals("小明", s.name("u1", "g1"))
        assertEquals("/c.png", s.avatarUrl("u1", "g1"))
        s.putMembers("g1", listOf(member("u1", avatar = "/m.png")))
        assertEquals("成员表头像优先", "/m.png", s.avatarUrl("u1", "g1"))
    }
}
