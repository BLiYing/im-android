package com.libeyond.imandroid.sdk.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvQueryFloorTest {
    private fun s(seq: Long) = ConvSearchItem(convSeq = seq)
    private fun m(seq: Long) = ConvMediaItem(convSeq = seq)

    @Test
    fun `位点为0条目原样返回`() {
        val p = ConvSearchPage(items = listOf(s(5)), nextCursor = 5, hasMore = true)
        assertEquals(p, ConvQueryFloor.search(p, 0))
    }

    @Test
    fun `搜索 has_more 即使没清空也要求游标大于0`() {
        // 服务端异常回 has_more=true 却 next_cursor=0：拿 0 去翻会回到最新页，▲ 永不停
        val p = ConvSearchPage(items = listOf(s(5)), nextCursor = 0, hasMore = true)
        assertFalse(ConvQueryFloor.search(p, 0).hasMore)
    }

    @Test
    fun `搜索结果滤掉位点以内的命中`() {
        val p = ConvSearchPage(items = listOf(s(90), s(60), s(50)), nextCursor = 50, hasMore = false)
        assertEquals(listOf(90L, 60L), ConvQueryFloor.search(p, 50).items.map { it.convSeq })
    }

    @Test
    fun `游标已落到位点之内就别再翻`() {
        // 游标 = 上一页最后一条；之后的页只会更小，全在位点以内
        assertFalse(ConvQueryFloor.hasMoreAboveFloor(true, nextCursor = 51, clearedUpTo = 50))
        assertTrue(ConvQueryFloor.hasMoreAboveFloor(true, nextCursor = 52, clearedUpTo = 50))
        assertFalse(ConvQueryFloor.hasMoreAboveFloor(false, nextCursor = 90, clearedUpTo = 50))
        assertFalse(ConvQueryFloor.hasMoreAboveFloor(true, nextCursor = 0, clearedUpTo = 50))
        val p = ConvSearchPage(items = listOf(s(60)), nextCursor = 51, hasMore = true)
        assertFalse(ConvQueryFloor.search(p, 50).hasMore)
    }

    @Test
    fun `媒体同样过滤`() {
        val p = ConvMediaPage(items = listOf(m(70), m(40)), nextCursor = 40, hasMore = true)
        val r = ConvQueryFloor.media(p, 50)
        assertEquals(listOf(70L), r.items.map { it.convSeq })
        assertFalse(r.hasMore)
    }

    @Test
    fun `日历整天丢掉当天第一条在位点以内的日子`() {
        val r = ConvCalendarResult(days = listOf(
            ConvCalendarDay(dayStartMs = 1, count = 3, firstConvSeq = 10),
            ConvCalendarDay(dayStartMs = 2, count = 2, firstConvSeq = 51),
        ))
        assertEquals(listOf(2L), ConvQueryFloor.calendar(r, 50).days.map { it.dayStartMs })
    }
}
