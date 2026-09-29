package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AppearancePrefs
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlin.math.roundToInt

/** 滑块页调哪一项（iOS `IMAppearanceSliderKind`）。 */
enum class AppearanceSliderKind(val min: Int, val max: Int) {
    FontSize(AppearancePrefs.FONT_MIN, AppearancePrefs.FONT_MAX),
    BubbleRadius(AppearancePrefs.RADIUS_MIN, AppearancePrefs.RADIUS_MAX),
}

/**
 * 字号 / 信息框圆角的滑块页（对齐 iOS `IMAppearanceSliderViewController`）：上面铺满一张聊天预览，
 * 下面一块圆顶面板放「小 A — 滑块 — 大 A」。
 *
 * **拖动即写入**（每跨一个整数就生效一次，已打开的聊天页也跟着变），「取消」恢复进页时的值、
 * 「设置」只是离开——与 iOS 一致。iOS 这页是模态弹出，本端用 push 转场 + 同样的左右两键（正当差异）。
 */
@Composable
fun AppearanceSliderScreen(
    kind: AppearanceSliderKind,
    prefs: AppearancePrefs,
    onValue: (Int) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val c = IMTheme.colors
    val value = if (kind == AppearanceSliderKind.FontSize) prefs.chatFontSize else prefs.bubbleRadius
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = stringResource(
                if (kind == AppearanceSliderKind.FontSize) R.string.appearance_font_size_title else R.string.appearance_bubble_radius_title,
            ),
            leftLabel = stringResource(R.string.common_cancel),
            onLeft = onCancel,
            actionText = stringResource(R.string.appearance_slider_done),
            onAction = onDone,
        )
        AppearanceChatPreview(
            theme = prefs.theme,
            wallpaper = prefs.wallpaper,
            fontSize = prefs.chatFontSize,
            bubbleRadius = prefs.bubbleRadius,
            expanded = true,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.cardBackground)
                .navigationBarsPadding()
                .height(138.dp)
                .padding(start = 22.dp, end = 22.dp, bottom = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("A", color = c.textSecondary, fontSize = 14.sp)
            Slider(
                value = value.toFloat(),
                onValueChange = { onValue(it.roundToInt()) },
                valueRange = kind.min.toFloat()..kind.max.toFloat(),
                // 整数档：M3 的 steps 是「两端之间的档数」
                steps = kind.max - kind.min - 1,
                colors = SliderDefaults.colors(
                    thumbColor = c.accent,
                    activeTrackColor = c.accent,
                    inactiveTrackColor = c.separator,
                    activeTickColor = c.accent,
                    inactiveTickColor = c.separator,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            )
            Text("A", color = c.textSecondary, fontSize = 30.sp)
        }
    }
}
