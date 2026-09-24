package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ConvCalendarDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 📅 日历跳转的纯逻辑（[ChatCalendar]）。分桶公式必须与后端 `ConvDayBuckets`
 * （`IMServer/internal/store/sqlite_message.go`）逐字一致，算法漂了的表现是"点这天跳到了另一天"。
 */
class ChatCalendarTest {

    private val eastEight = 8 * 3600_000L

    /** 2026-09-23T00:00:00+08:00 的 UTC 毫秒（= 2026-09-22T16:00:00Z）。 */
    private val localMidnight = 1790092800000L

    @Test
    fun `本地日起点按时区偏移整除分桶`() {
        // 2026-09-23 08:00:00 +08:00（= 2026-09-23T00:00:00Z）应落在同一本地日 00:00
        val ts = 1790121600000L
        assertEquals(localMidnight, ChatCalendar.dayStartMs(ts, eastEight))
    }

    @Test
    fun `同一本地日不同时刻分到同一个桶`() {
        val morning = ChatCalendar.dayStartMs(localMidnight + 1000L, eastEight) // 00:00:01
        val night = ChatCalendar.dayStartMs(localMidnight + 86_399_000L, eastEight) // 23:59:59
        assertEquals(morning, night)
    }

    @Test
    fun `跨天时刻分到不同的桶`() {
        val lastSecondOfDay = ChatCalendar.dayStartMs(localMidnight - 1000L, eastEight)
        val firstSecondOfNextDay = ChatCalendar.dayStartMs(localMidnight, eastEight)
        assert(lastSecondOfDay < firstSecondOfNextDay)
    }

    @Test
    fun `选中天有消息直接用当天首条`() {
        val days = listOf(
            ConvCalendarDay(dayStartMs = 100L, count = 2, firstConvSeq = 5),
            ConvCalendarDay(dayStartMs = 200L, count = 1, firstConvSeq = 9),
        )
        assertEquals(9L, ChatCalendar.firstSeqOnOrAfter(days, 200L))
    }

    @Test
    fun `选中天没消息退到下一个有消息的天`() {
        val days = listOf(
            ConvCalendarDay(dayStartMs = 100L, count = 2, firstConvSeq = 5),
            ConvCalendarDay(dayStartMs = 300L, count = 1, firstConvSeq = 9),
        )
        // 选中 200L（表里没有这天）应退到 300L 那天的首条
        assertEquals(9L, ChatCalendar.firstSeqOnOrAfter(days, 200L))
    }

    @Test
    fun `选中天之后表里再没有更晚的天回 null`() {
        val days = listOf(ConvCalendarDay(dayStartMs = 100L, count = 2, firstConvSeq = 5))
        assertNull(ChatCalendar.firstSeqOnOrAfter(days, 200L))
    }

    @Test
    fun `空表回 null`() {
        assertNull(ChatCalendar.firstSeqOnOrAfter(emptyList(), 100L))
    }
}
