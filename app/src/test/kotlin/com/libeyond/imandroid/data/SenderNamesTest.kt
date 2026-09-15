package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
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
}
