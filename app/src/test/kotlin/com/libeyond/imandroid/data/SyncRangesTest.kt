package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 区间代数的钉子。用例对应 im-web `src/sdk/ranges.test.ts` / `ranges.gap.test.ts`（对称的是不变式）。 */
class SyncRangesTest {
    private fun r(lo: Long, hi: Long) = SeqRange(lo, hi)

    @Test
    fun `归一化 排序并合并重叠`() {
        assertEquals(listOf(r(1, 12), r(20, 30)), SyncRanges.normalize(listOf(r(20, 30), r(1, 10), r(5, 12))))
    }

    @Test
    fun `相邻区间必须合并 否则拉全了也永远判成有缺口`() {
        assertEquals(listOf(r(1, 20)), SyncRanges.normalize(listOf(r(1, 10), r(11, 20))))
        assertEquals(listOf(r(1, 10), r(12, 20)), SyncRanges.normalize(listOf(r(1, 10), r(12, 20)))) // 差一格不并
    }

    @Test
    fun `丢弃非法区间而不是抛错`() {
        assertEquals(listOf(r(3, 5)), SyncRanges.normalize(listOf(r(0, 4), r(9, 2), r(-3, -1), r(3, 5))))
    }

    @Test
    fun `add 不修改入参 且非法入参当无事发生`() {
        val base = listOf(r(1, 10))
        val out = SyncRanges.add(base, 11, 20)
        assertEquals(listOf(r(1, 10)), base)
        assertEquals(listOf(r(1, 20)), out)
        assertEquals(base, SyncRanges.add(base, 9, 3))
        assertEquals(base, SyncRanges.add(base, 0, 0))
    }

    @Test
    fun `coversSpan 要求同一段完整覆盖 跨段不算`() {
        val rs = listOf(r(1, 10), r(20, 30))
        assertTrue(SyncRanges.coversSpan(rs, 3, 8))
        assertFalse(SyncRanges.coversSpan(rs, 8, 22)) // 横跨缺口
        assertFalse(SyncRanges.coversSpan(rs, 5, 4))
    }

    @Test
    fun `hasSeq 与 rangeContaining`() {
        val rs = listOf(r(1, 10), r(20, 30))
        assertTrue(SyncRanges.hasSeq(rs, 20))
        assertFalse(SyncRanges.hasSeq(rs, 15))
        assertEquals(r(20, 30), SyncRanges.rangeContaining(rs, 25))
        assertNull(SyncRanges.rangeContaining(rs, 15))
    }

    @Test
    fun `isComplete 有缺口即不齐 单段到 head 才齐`() {
        assertFalse(SyncRanges.isComplete(listOf(r(1, 10), r(20, 30)), 30))
        assertTrue(SyncRanges.isComplete(listOf(r(1, 30)), 30))
        assertFalse(SyncRanges.isComplete(listOf(r(1, 29)), 30)) // 尾巴差一条
    }

    @Test
    fun `isComplete 可见下界之下不算缺口 且可见范围内一条没有算齐`() {
        assertTrue(SyncRanges.isComplete(listOf(r(51, 100)), 100, floor = 50))
        assertTrue(SyncRanges.isComplete(emptyList(), 50, floor = 50))
        assertTrue(SyncRanges.isComplete(emptyList(), 0)) // head 未知也是 true——调用方自己排除「齐全但没东西」
    }

    @Test
    fun `contiguousUpTo 是 syncedConvSeq 的等价物`() {
        assertEquals(10L, SyncRanges.contiguousUpTo(listOf(r(1, 10), r(20, 30))))
        assertEquals(0L, SyncRanges.contiguousUpTo(listOf(r(20, 30)))) // 首段不从 1 起：游标保持原值
    }

    @Test
    fun `contiguousUpTo 带可见下界`() {
        assertEquals(100L, SyncRanges.contiguousUpTo(listOf(r(51, 100)), floor = 50))
        assertEquals(50L, SyncRanges.contiguousUpTo(listOf(r(60, 100)), floor = 50))
    }

    @Test
    fun `缺口只会收窄 补上中段即合并成一段`() {
        var rs = listOf(r(1, 100), r(300, 400))
        assertFalse(SyncRanges.isComplete(rs, 400))
        rs = SyncRanges.add(rs, 101, 299)
        assertEquals(listOf(r(1, 400)), rs)
        assertTrue(SyncRanges.isComplete(rs, 400))
    }

    @Test
    fun `反复登记同一段是幂等的`() {
        var rs = emptyList<SeqRange>()
        repeat(3) { rs = SyncRanges.add(rs, 5, 9) }
        assertEquals(listOf(r(5, 9)), rs)
    }

    @Test
    fun `mergeInto 把重叠与相邻的既有段并成一整段`() {
        assertEquals(r(1, 40), SyncRanges.mergeInto(listOf(r(1, 10), r(31, 40)), 11, 30))
        assertEquals(r(5, 9), SyncRanges.mergeInto(emptyList(), 5, 9))
        assertEquals(r(1, 3), SyncRanges.mergeInto(emptyList(), 0, 3)) // lo 夹到 1
    }

    @Test
    fun `spanOf 取合法序号的上下界 没有合法序号返回 null`() {
        assertEquals(r(3, 9), SyncRanges.spanOf(listOf(9, 3, 5, 0, -2)))
        assertNull(SyncRanges.spanOf(listOf(0, -1)))
        assertNull(SyncRanges.spanOf(emptyList()))
    }
}
