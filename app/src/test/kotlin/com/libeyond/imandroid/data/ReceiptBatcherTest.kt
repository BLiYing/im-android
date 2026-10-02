package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptBatcherTest {

    private fun TestScope.batcher(out: MutableList<Pair<String, Long>>) =
        ReceiptBatcher(this, { c, u -> out += c to u }, windowMs = 120)

    @Test
    fun `窗口内同一会话只发最大位点 一帧`() = runTest {
        val out = mutableListOf<Pair<String, Long>>()
        val b = batcher(out)
        (1L..500L).forEach { b.queue("c", it) } // 500 条补拉 = 500 次入队
        advanceTimeBy(119)
        assertEquals(emptyList<Pair<String, Long>>(), out) // 窗口没到不发
        advanceTimeBy(2)
        assertEquals(listOf("c" to 500L), out)
    }

    @Test
    fun `位点只增不减 乱序入队取最大`() = runTest {
        val out = mutableListOf<Pair<String, Long>>()
        val b = batcher(out)
        b.queue("c", 9); b.queue("c", 3)
        advanceTimeBy(130)
        assertEquals(listOf("c" to 9L), out)
    }

    @Test
    fun `不同会话各发一帧`() = runTest {
        val out = mutableListOf<Pair<String, Long>>()
        val b = batcher(out)
        b.queue("a", 5); b.queue("b", 7)
        advanceTimeBy(130)
        assertEquals(setOf("a" to 5L, "b" to 7L), out.toSet())
        assertEquals(2, out.size)
    }

    @Test
    fun `发完之后的新入队重新排窗口`() = runTest {
        val out = mutableListOf<Pair<String, Long>>()
        val b = batcher(out)
        b.queue("c", 1); advanceTimeBy(130)
        b.queue("c", 2); advanceTimeBy(130)
        assertEquals(listOf("c" to 1L, "c" to 2L), out)
    }

    @Test
    fun `无效入参当无事发生`() = runTest {
        val out = mutableListOf<Pair<String, Long>>()
        val b = batcher(out)
        b.queue("", 5); b.queue("c", 0); b.queue("c", -1)
        advanceTimeBy(500)
        assertEquals(emptyList<Pair<String, Long>>(), out)
    }

    @Test
    fun `clear 丢掉攒着的 不会发到下个账号`() = runTest {
        val out = mutableListOf<Pair<String, Long>>()
        val b = batcher(out)
        b.queue("c", 5); b.clear()
        advanceTimeBy(500)
        assertEquals(emptyList<Pair<String, Long>>(), out)
    }

    private fun md(seq: Long, from: String) = MessageData(convId = "c", convSeq = seq, from = from)

    @Test
    fun `sync 页位点取别人发的最大 seq 自己发的不算`() {
        val page = listOf(md(1, "peer"), md(2, "me"), md(3, "peer"), md(4, "me"))
        assertEquals(3L, DeliveredUpTo.forSyncPage(page, "me", null))
    }

    @Test
    fun `落库失败的那条及之后不回`() {
        val page = listOf(md(1, "peer"), md(2, "peer"), md(3, "peer"))
        assertEquals(1L, DeliveredUpTo.forSyncPage(page, "me", firstFailedSeq = 2))
    }

    @Test
    fun `空页与全是自己发的得 0`() {
        assertEquals(0L, DeliveredUpTo.forSyncPage(emptyList(), "me", null))
        assertEquals(0L, DeliveredUpTo.forSyncPage(listOf(md(1, "me")), "me", null))
    }
}
