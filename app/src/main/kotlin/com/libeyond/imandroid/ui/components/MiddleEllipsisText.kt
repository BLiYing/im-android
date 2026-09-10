package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import com.libeyond.imandroid.data.MiddleEllipsis

/**
 * 放不下时**截中间**的文本（「季度报表-最…改3.xlsx」），文件名用。
 *
 * foundation 1.7 没有 `TextOverflow.MiddleEllipsis`：这里量出可用宽度，
 * 用 [MiddleEllipsis.fit] 二分出最长的「开头…结尾」，按保留字符数单调的前提逐次实测排版。
 * 结果按 (文本, 样式, 行数, 宽度) 记住，列表滚动时不会每帧重量。
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    maxLines: Int,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    // 量与画必须是同一份样式：Text 默认会并入 LocalTextStyle（行高等），量的时候漏了就会判错放不放得下
    val style = LocalTextStyle.current.merge(TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight))
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val shown = remember(text, style, maxLines, maxW, bounded) {
            if (!bounded) {
                text
            } else {
                MiddleEllipsis.fit(text) { candidate ->
                    !measurer.measure(
                        candidate, style, maxLines = maxLines, constraints = Constraints(maxWidth = maxW),
                    ).hasVisualOverflow
                }
            }
        }
        Text(shown, style = style, maxLines = maxLines, overflow = TextOverflow.Clip)
    }
}
