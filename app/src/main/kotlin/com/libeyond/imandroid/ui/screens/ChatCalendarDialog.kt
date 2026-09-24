package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.ui.theme.IMTheme
import java.util.Calendar
import java.util.TimeZone

/**
 * 📅 日历跳转弹层（对齐 iOS `IMChatDateJumpViewController`：系统日历 + 「最早」「今天」两个快捷项）。
 *
 * **不画"哪些天有消息"的圆点装饰**：iOS 那份是靠本地/服务端合并出的打点集合逐天渲染，
 * Material3 `DatePicker` 没有现成的"某天加装饰"钩子，要做等于自绘一份日历——装饰只是锦上添花，
 * 跳转会不会跳对（本地/服务端谁给答案）才是这个功能的核心正确性，已经在 [com.libeyond.imandroid.data.ChatCalendar] /
 * `ChatCalendarState` 里做对了。**所有天都可点**，没消息的天点了会自然退到下一个有消息的日子
 * （同 iOS："没有'不可点'这回事"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatCalendarDialog(
    onDismiss: () -> Unit,
    /** 用户选中那天、**本地时区**的 00:00 对应 UTC 毫秒（已做过 UTC picker → 本地日的换算）。 */
    onPickDay: (localDayStartMs: Long) -> Unit,
    onEarliest: () -> Unit,
    onToday: () -> Unit,
) {
    val c = IMTheme.colors
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis
                    if (picked != null) onPickDay(localDayStartOf(picked)) else onDismiss()
                },
            ) { Text("跳转", color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    ) {
        // DatePickerDialog 内部用 Box 装 content()，多个直接子项会互相叠放而不是纵向排列
        // （曾在真机上验证「最早/今天」被 DatePicker 盖住、连无障碍树都摸不到）——这里显式套一层
        // Column 强制纵向堆叠，别删掉当作多余包装。
        Column {
            // 两个快捷项：最早 / 今天。放在系统日历上方，点了直接跳、不必再点「跳转」确认
            // （对齐 iOS 底部的两枚快捷钮，同样是点了立刻生效）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(
                    "最早",
                    color = c.accent,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onEarliest),
                )
                Text(
                    "今天",
                    color = c.accent,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onToday),
                )
            }
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

/**
 * Material3 `DatePicker` 回的是**时区无关**的 UTC 毫秒（用户在界面上选中那一天的 UTC 00:00），
 * 不是本地日 00:00——两者在非 UTC 时区下相差一个时区偏移。取出年月日后按**本地时区**重建 00:00，
 * 才能对上 [com.libeyond.imandroid.data.ChatCalendar.dayStartMs] 与本地库/服务端日历的分桶口径。
 */
private fun localDayStartOf(utcPickerMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcPickerMillis }
    val y = utc.get(Calendar.YEAR)
    val m = utc.get(Calendar.MONTH)
    val d = utc.get(Calendar.DAY_OF_MONTH)
    val local = Calendar.getInstance().apply {
        clear()
        set(y, m, d, 0, 0, 0)
    }
    return local.timeInMillis
}
