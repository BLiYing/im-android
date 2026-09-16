package com.libeyond.imandroid.ui.components

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 归档行的时间口径（[TimeFormat.fileDateTime]，对齐 iOS `IMFormatFileDateTime`）。
 *
 * 归档（详情页的媒体 / 文件 / 语音 / 链接页签）是**翻历史**的地方，
 * 用会话列表那套"今天显 HH:mm、昨天显『昨天』"的相对口径等于什么都没说
 * ——用户看到"昨天"却不知道是哪天几点。2026-09-17 与 iOS 对齐时改的，这条钉住它。
 */
class ArchiveTimeFormatTest {

    private fun at(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month0, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `年月日加时分，分钟补两位`() {
        assertEquals("2026年9月17日 01:05", TimeFormat.fileDateTime(at(2026, Calendar.SEPTEMBER, 17, 1, 5)))
        assertEquals("2026年12月1日 23:59", TimeFormat.fileDateTime(at(2026, Calendar.DECEMBER, 1, 23, 59)))
    }

    /** 没有时间戳就什么都不显示——**不能回落成 1970 年**（那比空着更像坏了）。 */
    @Test
    fun `时间戳缺失回空串`() {
        assertEquals("", TimeFormat.fileDateTime(0))
        assertEquals("", TimeFormat.fileDateTime(-1))
    }

    /**
     * 与相对口径**必须不同**：这条是反向护栏——哪天有人顺手把 `fileDateTime` 改回
     * 转调 [TimeFormat.conversationTime]，今天的那条会同时满足两边而悄悄溜过去，
     * 所以这里挑的是"今天"这个最容易被混淆的时刻。
     */
    @Test
    fun `今天的条目也要显完整日期，不是 HH mm`() {
        val now = System.currentTimeMillis()
        val full = TimeFormat.fileDateTime(now)
        assertEquals(true, full.contains("年") && full.contains("月") && full.contains("日"))
    }
}
