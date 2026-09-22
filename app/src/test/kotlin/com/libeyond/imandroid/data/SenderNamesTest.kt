package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊气泡发送者名字的取值链（[SenderNames]）。与 iOS `IMGroupSenderNameTests`、
 * im-web `chatNaming.test.ts` 钉的是同一条：备注 > 成员表 > 本窗最新快照 > 本条快照 > 好友昵称。
 */
class SenderNamesTest {

    private fun m(sender: String, nick: String?, seq: Long) =
        MessageEntity(ownerUid = "me", convId = "g1", convSeq = seq, sender = sender, fromNickname = nick)

    @Test
    fun `成员表压过老快照——改名后老消息也显示新名`() {
        assertEquals("新昵称", SenderNames.bubbleName(null, "新昵称", "新昵称", "旧昵称", null))
        assertEquals("新昵称", SenderNames.bubbleName(null, "新昵称", null, "旧昵称", "好友昵称"))
    }

    @Test
    fun `备注压过一切，好友昵称垫底`() {
        assertEquals("老王", SenderNames.bubbleName("老王", "新昵称", "新昵称", "旧昵称", "好友昵称"))
        assertEquals("好友昵称", SenderNames.bubbleName("", null, " ", null, "好友昵称"))
        assertNull(SenderNames.bubbleName(null, null, null, null, null))
    }

    @Test
    fun `超级群成员表只有自己——取本窗该发送者最新一条的快照`() {
        val window = listOf(m("a", "旧昵称", 1), m("b", "别人", 2), m("a", "新昵称", 3), m("a", null, 4), m("b", "", 5))
        val latest = SenderNames.latestNicknames(window)
        assertEquals("新昵称", latest["a"])
        assertEquals("别人", latest["b"])
        assertNull(latest["c"])
        assertEquals("新昵称", SenderNames.bubbleName(null, null, latest["a"], "旧昵称", null))
    }

    @Test
    fun `会话开着时对方改名再发消息——成员表两边都有且不同才算过期`() {
        assertTrue(SenderNames.memberNameStale("旧昵称", "新昵称"))
        assertFalse(SenderNames.memberNameStale("新昵称", "新昵称"))
        assertFalse(SenderNames.memberNameStale(null, "新昵称"))
        assertFalse(SenderNames.memberNameStale("旧昵称", null))
        assertFalse(SenderNames.memberNameStale("旧昵称", ""))
    }

    @Test
    fun `群资料页语音页签发送者名——自己 大于 成员表 大于 本地昵称快照`() {
        val members = listOf(GroupMember(userId = "a", nickname = "老王", groupNickname = "群昵称"))
        val nameOf = groupVoiceSenderNameOf("me", members, mapOf("b" to "本地快照昵称"))
        assertEquals("你自己", nameOf("me"))
        assertEquals("群昵称", nameOf("a"))       // 成员表：群昵称压过全局昵称
        assertEquals("本地快照昵称", nameOf("b")) // 成员表查不到（超级群只回自己）才兜底本地快照
        assertEquals("", nameOf("c"))            // 三档都空——整行不画，不落内部 uid
    }

    /** [myUid] 可空且不做 `.orEmpty()`：未登录（uid 尚未取到）时不该把空串误判成「我自己」发的。 */
    @Test
    fun `myUid 为空时不误判为自己`() {
        val nameOf = groupVoiceSenderNameOf(null, emptyList(), emptyMap())
        assertEquals("", nameOf(""))
    }
}
