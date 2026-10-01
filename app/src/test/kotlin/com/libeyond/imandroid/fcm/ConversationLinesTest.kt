package com.libeyond.imandroid.fcm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ConversationLines]：一个会话那条通知列哪几行、代表几条（PUSH_M5_DESIGN §3.7）。 */
class ConversationLinesTest {

    private fun line(seq: Long) = ConversationLine(seq, "小明", "第 $seq 条", seq * 1000)

    private fun linesOf(vararg seqs: Long) =
        seqs.fold(ConversationLines.EMPTY) { acc, s -> acc.append(line(s)) }

    @Test
    fun `连发几条——一条通知里逐行列出，条数跟着涨`() {
        val s = linesOf(1, 2, 3)
        assertEquals(listOf(1L, 2L, 3L), s.lines.map { it.seq })
        assertEquals(3, s.total)
    }

    @Test
    fun `超过上限只留最近几行，但条数照实算`() {
        val s = linesOf(*(1L..10L).toList().toLongArray())
        assertEquals(ConversationLines.MAX_LINES, s.lines.size)
        assertEquals(10L, s.lines.last().seq)
        assertEquals(10, s.total)
    }

    @Test
    fun `同一条重投不重复计`() {
        val s = linesOf(1, 2)
        assertSame(s, s.append(line(2)))
    }

    @Test
    fun `撤回中间一条——只去掉那一行，条数减一`() {
        val s = linesOf(1, 2, 3).withoutSeq(2)
        assertEquals(listOf(1L, 3L), s.lines.map { it.seq })
        assertEquals(2, s.total)
    }

    @Test
    fun `撤回的不在列表里——不动`() {
        val s = linesOf(1, 2)
        assertSame(s, s.withoutSeq(9))
    }

    @Test
    fun `别处读到一半——读过的行去掉，后来的留着`() {
        val s = linesOf(1, 2, 3).readThrough(2)
        assertEquals(listOf(3L), s.lines.map { it.seq })
        assertEquals(1, s.total)
    }

    @Test
    fun `全读完——一行不剩（调用方据此取消通知）`() {
        assertTrue(linesOf(1, 2).readThrough(5).isEmpty)
    }

    @Test
    fun `撤回一条早被挤出显示的消息——行不变，条数照样减一`() {
        val s = linesOf(*(1L..10L).toList().toLongArray()).withoutSeq(2)
        assertEquals((5L..10L).toList(), s.lines.map { it.seq })
        assertEquals(9, s.total)
    }

    @Test
    fun `读到的位置比显示的最早一行还早——行不变，读掉的那几条照样扣`() {
        val s = linesOf(*(1L..10L).toList().toLongArray()).readThrough(3)
        assertEquals((5L..10L).toList(), s.lines.map { it.seq })
        assertEquals(7, s.total)
    }

    @Test
    fun `读到显示的中间——读过的行去掉，条数等于剩下的`() {
        val s = linesOf(*(1L..10L).toList().toLongArray()).readThrough(7)
        assertEquals(listOf(8L, 9L, 10L), s.lines.map { it.seq })
        assertEquals(3, s.total)
    }

    @Test
    fun `存进通知 extras 再读回来——原样`() {
        val s = linesOf(*(1L..8L).toList().toLongArray())
        val back = ConversationLines.fromArrays(s.lineSeqs, s.lineSenders, s.lineTexts, s.lineTimes, s.allSeqs)
        assertEquals(s, back)
        assertArrayEquals((1L..8L).toList().toLongArray(), back!!.allSeqs)
    }

    @Test
    fun `extras 缺一项或长度对不上——认不出，返回 null`() {
        val s = linesOf(1, 2)
        assertNull(ConversationLines.fromArrays(s.lineSeqs, null, s.lineTexts, s.lineTimes, s.allSeqs))
        assertNull(ConversationLines.fromArrays(s.lineSeqs, arrayOf("x"), s.lineTexts, s.lineTimes, s.allSeqs))
    }

    @Test
    fun `早一版写的通知没有全部序号——按显示的几行算`() {
        val s = linesOf(1, 2)
        assertEquals(2, ConversationLines.fromArrays(s.lineSeqs, s.lineSenders, s.lineTexts, s.lineTimes, null)?.total)
    }
}
