package com.libeyond.imandroid.rtc

import com.imrtc.uikit.IMInviteContext
import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtcInviteProviderTest {

    private fun ctx(participants: List<String>) = IMInviteContext(
        callId = "c1", chatGroupId = "g1", userData = "", callerUid = "me",
        mediaType = "video", participantUids = participants, slotsLeft = 9 - participants.size,
    )

    private fun member(uid: String, nick: String = "", group: String = "", user: String = "", avatar: String = "") =
        GroupMember(userId = uid, username = user, nickname = nick, groupNickname = group, avatarUrl = avatar)

    @Test fun `剔掉自己，已在通话的置灰，其余可选`() {
        val out = RtcInviteProvider.candidates(
            ctx(listOf("me", "b")),
            listOf(member("me", "我"), member("a", "甲"), member("b", "乙")),
            "me", RtcProfileSources(),
        ) { it }
        assertEquals(listOf("a", "b"), out.map { it.uid })
        assertTrue(out[0].selectable)
        assertFalse(out[1].selectable)
        assertEquals("已在通话中", out[1].unselectableReason)
    }

    @Test fun `名字走群昵称 昵称 句柄 链，头像转绝对地址`() {
        val out = RtcInviteProvider.candidates(
            ctx(listOf("me")),
            listOf(member("a", "甲", group = "群里的甲", avatar = "/m/a.png"), member("c", user = "cc")),
            "me", RtcProfileSources(),
        ) { "http://h$it" }
        assertEquals("群里的甲", out[0].name)
        assertEquals("http://h/m/a.png", out[0].avatarUrl)
        assertEquals("@cc", out[1].name)
        assertNull(out[1].avatarUrl)
    }
}
