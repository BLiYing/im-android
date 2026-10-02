package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStubTest {
    @Test fun `单聊壳带上对端uid，无论我是字典序前一个还是后一个`() {
        val a = newConversationStub("1199353701", "u_1000156391_u_1199353701")
        assertEquals("1000156391", a.peerUid)
        assertFalse(a.isGroup)
        assertEquals("", a.title)
        val b = newConversationStub("1000156391", "u_1000156391_u_1199353701")
        assertEquals("1199353701", b.peerUid)
    }

    @Test fun `群壳不带peerUid`() {
        val g = newConversationStub("1199353701", "g_abc")
        assertTrue(g.isGroup)
        assertEquals("", g.peerUid)
    }

    @Test fun `畸形convId当群聊处理且peerUid为空（钉住现有行为）`() {
        val s = newConversationStub("1199353701", "u_111_u_222") // 两段都不是我
        assertTrue(s.isGroup)
        assertEquals("", s.peerUid)
    }

    private fun row(seq: Long, last: String, unread: Int) =
        com.libeyond.imandroid.data.db.ConversationEntity(
            ownerUid = "me", convId = "c", lastContent = last, lastConvSeq = seq, unread = unread, title = "t",
        )

    @Test fun `快照比本地旧时保留本地的预览与未读，标题仍取快照`() {
        val snapshot = row(seq = 1, last = "旧", unread = 1).copy(title = "新名字")
        val merged = snapshot.keepNewerLocalTail(row(seq = 2, last = "新", unread = 2))
        assertEquals("新", merged.lastContent)
        assertEquals(2L, merged.lastConvSeq)
        assertEquals(2, merged.unread)
        assertEquals("新名字", merged.title)
    }

    @Test fun `快照不旧于本地时原样采用快照`() {
        val snapshot = row(seq = 2, last = "快照", unread = 0)
        assertEquals(snapshot, snapshot.keepNewerLocalTail(row(seq = 2, last = "本地", unread = 5)))
        assertEquals(snapshot, snapshot.keepNewerLocalTail(null))
    }
}
