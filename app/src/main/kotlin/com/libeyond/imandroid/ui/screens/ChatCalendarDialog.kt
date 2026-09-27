package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatCalendar
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.data.ResolvedLanguage
import com.libeyond.imandroid.ui.theme.IMTheme
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone

/**
 * 📅 日历跳转弹层（对齐 iOS `IMChatDateJumpViewController`：系统日历 + 「最早」「今天」两个快捷项 +
 * 有消息的天下方一颗圆点）。
 *
 * **自绘月历网格，不用 Material3 `DatePicker`**：M3 `DatePicker`（1.3.x）没有"给某一天加装饰"的公开钩子
 * ——想在它上面叠圆点，要么等它加这个 API，要么整块换成自绘。iOS `UICalendarView` 有
 * `decorationForDateComponents:` 这个钩子，是两端实现分叉的根源，不是本端没做到位。
 *
 * 圆点数据来自 [activeDays]（本地时区分桶 ms 集合，调用方 `ChatCalendarState.kt` 里
 * 本地打点 ∪ 服务端打点合并好才传进来），查表用的桶 key 与 [onPickDay] 回传坐标同一套口径
 * （[ChatCalendar.dayStartMs]）——网格上点哪天、判它有没有点、跳转跳到哪，三处必须用同一份算法，
 * 用三份就会出现"点了看着有点的那天，跳转却落到隔壁"。
 */
@Composable
internal fun ChatCalendarDialog(
    onDismiss: () -> Unit,
    /** 用户选中那天、**本地时区**的 00:00 对应 UTC 毫秒（已做过换算）。 */
    onPickDay: (localDayStartMs: Long) -> Unit,
    onEarliest: () -> Unit,
    onToday: () -> Unit,
    /** 有消息的整天集合（本地时区分桶 ms），见 [com.libeyond.imandroid.ui.ChatCalendarController.activeDays]。 */
    activeDays: Set<Long>,
) {
    val c = IMTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    // 与 activeDays 是同一份固定 offset（弹层打开那一刻算一次），不逐天用 zone 规则重算——
    // 三处（打点集合/网格查表/跳转坐标）必须共用一份，见类注释。
    val offsetMs = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() }
    var displayedMonth by remember { mutableStateOf(YearMonth.now(zone)) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.chat_search_date_jump_dialog_title),
                color = c.textPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(
                        stringResource(R.string.chat_search_earliest),
                        color = c.accent,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(onClick = onEarliest),
                    )
                    Text(
                        stringResource(R.string.time_today),
                        color = c.accent,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(onClick = onToday),
                    )
                }
                IMCalendarGrid(
                    displayedMonth = displayedMonth,
                    onMonthChange = { displayedMonth = it },
                    selected = selected,
                    onSelect = { selected = it },
                    activeDays = activeDays,
                    offsetMs = offsetMs,
                    zone = zone,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val day = selected
                    if (day != null) onPickDay(day.dayStartMsKey(zone, offsetMs)) else onDismiss()
                },
            ) { Text(stringResource(R.string.chat_search_date_jump_confirm), color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = c.textSecondary)
            }
        },
        containerColor = c.surfaceElevated,
    )
}

/**
 * 星期表头文案。**不能是顶层 val**（禁止在顶层初始化时求值文案，切语言不会变），
 * 改成组合期按 [R.string.chat_search_dow_sun] 等一读七个（复用会话内搜索日历同一套键）。
 */
@Composable
private fun weekdayLabels(): List<String> = listOf(
    stringResource(R.string.chat_search_dow_sun),
    stringResource(R.string.chat_search_dow_mon),
    stringResource(R.string.chat_search_dow_tue),
    stringResource(R.string.chat_search_dow_wed),
    stringResource(R.string.chat_search_dow_thu),
    stringResource(R.string.chat_search_dow_fri),
    stringResource(R.string.chat_search_dow_sat),
)

/**
 * 月历头部「年月」的 month 参数（对齐 `chat.search.calendar_month_label` 的口径）：
 * zh 传数字串、en 传英文月份缩写（如 Sep）——不是简单的数字格式化。
 */
private fun monthArg(month: Int): String = if (LanguageStore.resolved == ResolvedLanguage.EN) {
    Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
} else {
    month.toString()
}

@Composable
private fun IMCalendarGrid(
    displayedMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    activeDays: Set<Long>,
    offsetMs: Long,
    zone: ZoneId,
) {
    val c = IMTheme.colors

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonthNavButton(Lucide.ChevronLeft, stringResource(R.string.chat_search_prev_month)) {
                onMonthChange(displayedMonth.minusMonths(1))
            }
            Text(
                stringResource(
                    R.string.chat_search_calendar_month_label,
                    displayedMonth.year.toString(),
                    monthArg(displayedMonth.monthValue),
                ),
                color = c.textPrimary,
                style = MaterialTheme.typography.titleSmall,
            )
            MonthNavButton(Lucide.ChevronRight, stringResource(R.string.chat_search_next_month)) {
                onMonthChange(displayedMonth.plusMonths(1))
            }
        }

        Row(Modifier.fillMaxWidth()) {
            weekdayLabels().forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, color = c.textTertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val firstOfMonth = displayedMonth.atDay(1)
        // DayOfWeek：MON=1..SUN=7。取 %7 把 SUN 归到第 0 列，网格从周日起头（对齐系统日历习惯）。
        val leading = firstOfMonth.dayOfWeek.value % 7
        val daysInMonth = displayedMonth.lengthOfMonth()
        val rows = (leading + daysInMonth + 6) / 7

        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = row * 7 + col - leading + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..daysInMonth) {
                            val date = displayedMonth.atDay(dayNum)
                            val key = date.dayStartMsKey(zone, offsetMs)
                            DayCell(
                                dayNum = dayNum,
                                selected = date == selected,
                                hasMessage = activeDays.contains(key),
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(dayNum: Int, selected: Boolean, hasMessage: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier.size(32.dp)
                .clip(CircleShape)
                .background(if (selected) c.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$dayNum",
                color = if (selected) c.onAccent else c.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(Modifier.size(4.dp)) {
            if (hasMessage) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(c.accent))
            }
        }
    }
}

@Composable
private fun MonthNavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(c.textSecondary),
        )
    }
}

/**
 * 这一天本地时区 00:00 对应的 UTC 毫秒，按 [ChatCalendar.dayStartMs] 同一份公式算
 * （用当天正午当参照点，避免月初/月末在个别时区下被 `atStartOfDay` 的夏令时规则牵动半小时/一小时）。
 */
private fun LocalDate.dayStartMsKey(zone: ZoneId, offsetMs: Long): Long {
    val noonMs = atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    return ChatCalendar.dayStartMs(noonMs, offsetMs)
}
