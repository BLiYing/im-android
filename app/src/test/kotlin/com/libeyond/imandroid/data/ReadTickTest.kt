package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ReadBy
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已读勾的取值与落库规则（对齐 iOS：群聊复用 peerReadSeq，群里 ✓✓ = 所有人都读过）。 */
class ReadTickTest {
    @Test fun `超级群隐藏勾，普通群与单聊取读位点`() {
        assertEquals(ReadTick.HIDDEN, ReadTick.seqFor(isGroup = true, isSuper = true, peerReadSeq = 9))
        assertEquals(9L, ReadTick.seqFor(isGroup = true, isSuper = false, peerReadSeq = 9))
        assertEquals(9L, ReadTick.seqFor(isGroup = false, isSuper = false, peerReadSeq = 9))
    }

    @Test fun `群快照落库取 group_read_seq 且只增不减`() {
        assertEquals(7L, ReadTick.seed(isGroup = true, existing = 3, peerReadSeq = 0, groupReadSeq = 7))
        assertEquals("过期快照不能把已绿的勾退回去", 7L, ReadTick.seed(true, existing = 7, peerReadSeq = 0, groupReadSeq = 2))
    }

    @Test fun `单聊落库照旧取服务端 peer_read_seq`() =
        assertEquals(4L, ReadTick.seed(isGroup = false, existing = 9, peerReadSeq = 4, groupReadSeq = 100))

    @Test fun `读位点越过这条才算已读，0 与隐藏都不算`() {
        assertTrue(ReadTick.isRead(10, 10))
        assertTrue(ReadTick.isRead(11, 10))
        assertFalse(ReadTick.isRead(9, 10))
        assertFalse("还没人读过", ReadTick.isRead(0, 1))
        assertFalse("隐藏", ReadTick.isRead(ReadTick.HIDDEN, 1))
    }

    @Test fun `已读名单解析，缺字段给默认`() {
        val r = ProtocolJson.decodeFromString(
            ReadBy.serializer(),
            """{"conv_seq":5,"read":["a","b"],"unread":["c"],"total":3,"enabled":true}""",
        )
        assertEquals(listOf("a", "b"), r.read)
        assertEquals(listOf("c"), r.unread)
        assertTrue(r.enabled)
        assertFalse("老/大群响应缺 enabled 就当没开", ProtocolJson.decodeFromString(ReadBy.serializer(), "{}").enabled)
    }
}
