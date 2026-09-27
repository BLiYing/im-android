package com.libeyond.imandroid.ui.components

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间格式化。纯函数（吃时间戳吐字符串），便于单测；不要在 Composable 里现算格式。
 *
 * **三端口径见 IMServer `docs/UI_SPEC.md` §5，动手改之前先读它。**
 * 目前 [dayLabel] 三端一致（§5.2），[conversationTime] **三端各不相同**（§5.1，基准待定）。
 */
object TimeFormat {

    /**
     * 会话列表右上角的时间。
     *
     * ⚠️ **这不是"与 iOS 对齐"的实现**（此处注释一度这么写，是错的）。实测三端各不相同：
     * iOS 是 `HH:mm` / 其余一律 `MM-dd`，Web 是恒 `HH:mm`（不区分日期，本身是 bug）。
     * 本实现走四段式，与 [dayLabel] 共用同一套词汇。基准待人拍板，见 `docs/UI_SPEC.md` §5.1。
     */
    fun conversationTime(tsMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (tsMillis <= 0) return ""
        val c = Calendar.getInstance().apply { timeInMillis = tsMillis }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        return when {
            isSameDay(c, n) -> String.format(
                Locale.getDefault(), "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
            )
            isYesterday(c, n) -> Str.s(R.string.time_yesterday)
            c.get(Calendar.YEAR) == n.get(Calendar.YEAR) ->
                Str.s(R.string.time_month_day, Str.monthArg(c), c.get(Calendar.DAY_OF_MONTH))
            else ->
                Str.s(
                    R.string.time_full_date,
                    c.get(Calendar.YEAR).toString(), Str.monthArg(c), c.get(Calendar.DAY_OF_MONTH),
                )
        }
    }

    /**
     * 归档（详情页的媒体 / 文件 / 语音 / 链接页签）里那一行时间：**完整的年月日 + 时分**。
     *
     * 对齐 iOS `IMFormatFileDateTime`（文件行、链接行、语音行第三行都用它）。
     * **不能用 [conversationTime]**：那是"今天显 HH:mm、昨天显『昨天』"的相对口径，
     * 放在归档里等于告诉用户"这个文件是昨天的"却不说是哪天几点——
     * 归档是翻历史的地方，相对时间在这里没有意义（2026-09-17 与 iOS 对齐时改的）。
     */
    fun fileDateTime(tsMillis: Long): String {
        if (tsMillis <= 0) return ""
        val c = Calendar.getInstance().apply { timeInMillis = tsMillis }
        val datePart = Str.s(
            R.string.time_full_date,
            c.get(Calendar.YEAR).toString(), Str.monthArg(c), c.get(Calendar.DAY_OF_MONTH),
        )
        val timePart = String.format(
            Locale.getDefault(), "%02d:%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
        return "$datePart $timePart"
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
            isSameDay(c, n) -> Str.s(R.string.time_today)
            isYesterday(c, n) -> Str.s(R.string.time_yesterday)
            c.get(Calendar.YEAR) == n.get(Calendar.YEAR) ->
                Str.s(R.string.time_month_day, Str.monthArg(c), c.get(Calendar.DAY_OF_MONTH))
            else ->
                Str.s(
                    R.string.time_full_date,
                    c.get(Calendar.YEAR).toString(), Str.monthArg(c), c.get(Calendar.DAY_OF_MONTH),
                )
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
