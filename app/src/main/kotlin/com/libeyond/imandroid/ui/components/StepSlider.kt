package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlin.math.roundToInt

/** 禁用态整体透明度（iOS 两个自动下载设置页同为 0.4）。 */
private const val DISABLED_ALPHA = 0.4f

/**
 * 分档滑杆（放在 [IMSettingsGroup] 里）：标题 + 滑杆 +（可选）刻度名一行。
 * 对齐 iOS 自动下载设置页里手搓的 `UISlider` 行。
 *
 * **拖动只改显示，松手才 [onCommit]**（iOS 同为 TouchUpInside 才保存）：一次拖动划过十来档，
 * 每档一次整份 PUT 是纯浪费，乱序回来的应答还会让滑块来回跳。
 *
 * 拖动中的位置是本组件自己的状态，**以 [index] 与 [count] 为 key**：保存成功（档位变了）、
 * 保存失败回滚（档位变回去）、别的端改了推过来，都会让它回到外部真值上，不会停在手指松开的地方。
 *
 * @param title 显示中的档位 → 标题文案（拖动时跟着变）
 * @param index 已保存的值所在档
 */
@Composable
fun IMStepSlider(
    title: (Int) -> String,
    index: Int,
    count: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tickLabels: List<String> = emptyList(),
    enabled: Boolean = true,
) {
    val c = IMTheme.colors
    val last = (count - 1).coerceAtLeast(1)
    var position by remember(index, count) { mutableFloatStateOf(index.toFloat()) }
    val shown = position.roundToInt().coerceIn(0, count - 1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = IMTheme.dimens.space4, vertical = 10.dp),
    ) {
        Text(title(shown), color = c.textPrimary, fontSize = 15.sp)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onCommit(position.roundToInt().coerceIn(0, count - 1)) },
            valueRange = 0f..last.toFloat(),
            // steps 是两端**之间**的档数
            steps = (count - 2).coerceAtLeast(0),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.neutralControl,
                // 不画刻度点：13 档挤在一根轨道上全是点；档名由下面那一行文字给
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                // 禁用态不换色，整块已经按 DISABLED_ALPHA 淡掉了（再换一次色会淡两遍）
                disabledThumbColor = c.accent,
                disabledActiveTrackColor = c.accent,
                disabledInactiveTrackColor = c.neutralControl,
                disabledActiveTickColor = Color.Transparent,
                disabledInactiveTickColor = Color.Transparent,
            ),
        )
        if (tickLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                tickLabels.forEachIndexed { i, name ->
                    Text(
                        text = name,
                        color = if (i == shown) c.textPrimary else c.textSecondary,
                        fontSize = 11.sp,
                        // 首档靠左、末档靠右，与滑杆两端对齐（iOS 同）
                        textAlign = when (i) {
                            0 -> TextAlign.Start
                            tickLabels.lastIndex -> TextAlign.End
                            else -> TextAlign.Center
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
