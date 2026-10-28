package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ConversationSummary
import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除会话＝清到位点（OFFLINE_BACKLOG_DESIGN §6.7.1）的位点口径与线格式。仓库层落库见 androidTest `MessageRepositoryRangesTest`。 */
class ClearToFloorTest {
    @Test
    fun `有服务端位点取 max 本机位点与 cleared_at 不看本机 head`() {
        assertEquals(80L, ClearFloor.floorOnClear(localCleared = 50, serverClearedAt = 80, localHead = 200))
        assertEquals(60L, ClearFloor.floorOnClear(localCleared = 60, serverClearedAt = 40, localHead = 200))
    }

    @Test
    fun `回包缺 cleared_at 退回本机 head 且不低于已有位点`() {
        assertEquals(200L, ClearFloor.floorOnClear(localCleared = 50, serverClearedAt = 0, localHead = 200))
        assertEquals(50L, ClearFloor.floorOnClear(localCleared = 50, serverClearedAt = 0, localHead = 10))
        assertEquals(0L, ClearFloor.floorOnClear(0, 0, 0))
    }

    @Test
    fun `列表项 cleared_at 高过本机位点才触发例程`() {
        assertTrue(ClearFloor.listNeedsClear(localCleared = 10, serverClearedAt = 11))
        assertFalse(ClearFloor.listNeedsClear(localCleared = 11, serverClearedAt = 11))
        assertFalse(ClearFloor.listNeedsClear(localCleared = 0, serverClearedAt = 0)) // 字段缺省（omitempty）
    }

    @Test
    fun `会话列表与 conv_update 能解出 cleared_at`() {
        val s = ProtocolJson.decodeFromString(ConversationSummary.serializer(), """{"conv_id":"c","cleared_at":42}""")
        assertEquals(42L, s.clearedAt)
        assertEquals(0L, ProtocolJson.decodeFromString(ConversationSummary.serializer(), """{"conv_id":"c"}""").clearedAt)
        val u = ProtocolJson.decodeFromString(ConvUpdateData.serializer(), """{"conv_id":"c","action":"delete","cleared_at":7}""")
        assertEquals(7L, u.clearedAt)
    }

    @Test
    fun `区间清单裁到位点之后 整段在位点内的丢 跨位点的从 floor+1 起`() {
        val r = listOf(SeqRange(1, 5), SeqRange(8, 20), SeqRange(30, 40))
        assertEquals(listOf(SeqRange(8, 20), SeqRange(30, 40)), SyncRanges.clipAfter(r, 5))
        assertEquals(listOf(SeqRange(11, 20), SeqRange(30, 40)), SyncRanges.clipAfter(r, 10))
        assertEquals(listOf(SeqRange(36, 40)), SyncRanges.clipAfter(r, 35))
        assertEquals(emptyList<SeqRange>(), SyncRanges.clipAfter(r, 40))
        assertEquals(r, SyncRanges.clipAfter(r, 0))
    }

    @Test
    fun `回声帧晚于新消息 位点之上已有消息就不能移行`() {
        assertTrue(ClearFloor.revivedAfter(clearedAt = 7, lastConvSeq = 9, maxLocalSeq = 0))
        assertTrue(ClearFloor.revivedAfter(clearedAt = 7, lastConvSeq = 0, maxLocalSeq = 8))
        assertFalse(ClearFloor.revivedAfter(clearedAt = 7, lastConvSeq = 7, maxLocalSeq = 7))
        assertFalse(ClearFloor.revivedAfter(clearedAt = 0, lastConvSeq = 9, maxLocalSeq = 9)) // 无服务端位点 = 本机 head 口径，一律移
    }

    @Test
    fun `有效位点取行与独立表大者 壳行带位点起步`() {
        assertEquals(9L, ClearFloor.effective(rowFloor = 3, tableFloor = 9))
        assertEquals(3L, ClearFloor.effective(rowFloor = 3, tableFloor = 0))
        val stub = com.libeyond.imandroid.data.db.ConversationEntity(ownerUid = "me", convId = "c").withFloor(9)
        assertEquals(9L, stub.clearedUpTo)
        assertEquals(9L, stub.syncedConvSeq)
        assertEquals(0L, com.libeyond.imandroid.data.db.ConversationEntity(ownerUid = "me", convId = "c").withFloor(0).clearedUpTo)
    }
}
