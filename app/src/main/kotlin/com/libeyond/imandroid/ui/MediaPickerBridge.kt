package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.mediapicker.MediaPickerLog
import com.libeyond.mediapicker.MediaPickerSkin

/**
 * `:media-picker` 模块的接线层。
 *
 * 模块**不认识** `IMTheme` 也**不认识** `IMLog`（依赖方向单向：app → media-picker），
 * 所以这两个接缝在这里对接。整个 app 里只有这一个文件 import `com.libeyond.mediapicker.*`
 * 之外的接线细节——要换掉选择器实现，改这一个文件就够。
 */
@Composable
internal fun rememberPickerSkin(): MediaPickerSkin {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    return remember(c, d) {
        MediaPickerSkin(
            accent = c.accent,
            onAccent = c.onAccent,
            pageBackground = c.pageBackground,
            surface = c.surface,
            surfaceElevated = c.surfaceElevated,
            textPrimary = c.textPrimary,
            textSecondary = c.textSecondary,
            overlay = c.overlay,
            subtleFill = c.subtleFill,
            separator = c.separator,
            space1 = d.space1,
            space2 = d.space2,
            space3 = d.space3,
            space4 = d.space4,
            radiusCard = d.radiusCard,
        )
    }
}

/**
 * 把模块的日志事件转发进 `IMLog`。
 *
 * 模块自己**绝不**打日志（`MediaPickerLog.None` 是静默丢弃，不是打 logcat）——
 * 直接用 `android.util.Log` 会撞三端日志红线（IMServer `docs/LOGGING.md` §7.1），
 * 而模块又不该 import 业务的 IMLog。转发是唯一两边都不破的解法。
 */
internal val PickerLog = MediaPickerLog { level, event, fields ->
    val tagged = IMLog.tag("IM.Media")
    val pairs = fields.entries.map { it.key to it.value }.toTypedArray()
    when (level) {
        MediaPickerLog.Level.DEBUG -> tagged.d(event, *pairs)
        MediaPickerLog.Level.WARN -> tagged.w(event, *pairs)
    }
}
