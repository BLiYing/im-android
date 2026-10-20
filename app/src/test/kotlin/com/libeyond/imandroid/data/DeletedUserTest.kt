package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ConversationSummary
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.protocol.Envelope
import com.libeyond.imandroid.sdk.ws.isAccountDeletedFrame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL §6.16：已注销用户一律「已注销用户」，压过备注 / 群昵称 / 会话备注；注销踢下线帧识别。 */
class DeletedUserTest {
    private val name = "已注销用户"

    @Test fun cardOverridesRemark() {
        val c = UserCard(userId = "u", nickname = "旧", remark = "老王", username = "x", deleted = true)
        assertEquals(name, c.displayName)
        assertEquals("", c.handle)
        assertEquals(name, c.normalized().nickname)
        assertEquals("", c.normalized().remark)
    }

    @Test fun friendOverridesRemark() {
        val f = FriendEntry(userId = "u", nickname = "旧", remark = "老王", username = "x", deleted = true)
        assertEquals(name, f.displayName)
        assertEquals("", f.normalized().avatarUrl)
        assertEquals("老王", f.copy(deleted = false).displayName)
    }

    @Test fun memberOverridesGroupNickname() {
        val m = GroupMember(userId = "u", nickname = "旧", groupNickname = "群内名", deleted = true)
        assertEquals(name, m.displayName)
        assertEquals("群内名", m.copy(deleted = false).displayName)
    }

    @Test fun conversationOverridesAllRemarks() {
        val s = ConversationSummary(convId = "c", peerNickname = "旧", peerRemark = "老王", remark = "会话备注", peerDeleted = true)
        assertEquals(name, DisplayName.ofConversation(s))
        val n = s.normalized()
        assertEquals(name, n.peerNickname)
        assertEquals("", n.peerRemark)
        // 群会话不受 peerDeleted 影响
        assertEquals("会话备注", DisplayName.ofConversation(s.copy(isGroup = true, peerDeleted = true)))
    }

    @Test fun accountDeletedFrameOnlyWithReason() {
        fun env(json: String) = Envelope("error", 0, Json.parseToJsonElement(json))
        assertTrue(isAccountDeletedFrame(env("""{"code":200005,"reason":"account_deleted","delete_at":1}""")))
        assertFalse(isAccountDeletedFrame(env("""{"code":200005}""")))
        assertFalse(isAccountDeletedFrame(env("""{"code":1,"reason":"other"}""")))
        assertFalse(isAccountDeletedFrame(Envelope("pong", 0, Json.parseToJsonElement("""{"reason":"account_deleted"}"""))))
        assertFalse(isAccountDeletedFrame(Envelope("error", 0, null)))
    }
}
