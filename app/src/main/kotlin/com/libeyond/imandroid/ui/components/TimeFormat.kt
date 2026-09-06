package com.libeyond.imandroid.ui.components

import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间格式化——与 iOS `IMTheme` 的会话列表口径对齐：
 * 今天显示 `HH:mm`、昨天显示「昨天」、同年显示 `M月d日`、跨年显示 `yyyy年M月d日`。
 *
 * 纯函数（吃时间戳吐字符串），便于单测；不要在 Composable 里现算格式。
 */
object TimeFormat {

    /** 会话列表右上角的时间。 */
    fun conversationTime(tsMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (tsMillis <= 0) return ""
        val c = Calendar.getInstance().apply { timeInMillis = tsMillis }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        return when {
            isSameDay(c, n) -> String.format(
                Locale.getDefault(), "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
            )
            isYesterday(c, n) -> "昨天"
            c.get(Calendar.YEAR) == n.get(Calendar.YEAR) ->
                "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
            else ->
                "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    /** 气泡内的时间，恒 `HH:mm`。 */
    fun bubbleTime(tsMillis: Long): String {
        if (tsMillis <= 0) return ""
        val c = Calendar.getInstance().apply { timeInMillis = tsMillis }
        return String.format(
            Locale.getDefault(), "%02d:%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    /** 消息列表里的日期分组胶囊。 */
    fun dayLabel(tsMillis: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = tsMillis }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        return when {
            isSameDay(c, n) -> "今天"
            isYesterday(c, n) -> "昨天"
            c.get(Calendar.YEAR) == n.get(Calendar.YEAR) ->
                "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
            else ->
                "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    /** 两条消息之间是否要插日期分隔。 */
    fun needsDaySeparator(prevTs: Long, curTs: Long): Boolean {
        if (prevTs <= 0) return true
        val a = Calendar.getInstance().apply { timeInMillis = prevTs }
        val b = Calendar.getInstance().apply { timeInMillis = curTs }
        return !isSameDay(a, b)
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(c: Calendar, now: Calendar): Boolean {
        val y = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return isSameDay(c, y)
    }
}
