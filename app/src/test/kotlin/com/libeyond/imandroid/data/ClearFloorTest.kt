package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MessageData
import org.junit.Assert.assertEquals
import org.junit.Test

class ClearFloorTest {
    private fun md(seq: Long) = MessageData(convId = "c", convSeq = seq)

    @Test
    fun `清空位点取本机所知最新位置`() {
        assertEquals(100L, ClearFloor.floorAtClear(head = 100, lastConvSeq = 90, synced = 5, maxLocalSeq = 5))
        assertEquals(90L, ClearFloor.floorAtClear(head = 0, lastConvSeq = 90, synced = 5, maxLocalSeq = 7))
        assertEquals(0L, ClearFloor.floorAtClear(0, 0, 0, 0))
    }

    @Test
    fun `dropCleared 丢掉位点及以下 位点之后照收`() {
        assertEquals(listOf(6L, 7L), ClearFloor.dropCleared(listOf(md(3), md(5), md(6), md(7)), 5).map { it.convSeq })
    }

    @Test
    fun `位点为 0 原样返回 且 seq 非正的行不误伤`() {
        val l = listOf(md(1), md(2))
        assertEquals(l, ClearFloor.dropCleared(l, 0))
        assertEquals(listOf(0L), ClearFloor.dropCleared(listOf(md(0)), 5).map { it.convSeq })
    }
}
