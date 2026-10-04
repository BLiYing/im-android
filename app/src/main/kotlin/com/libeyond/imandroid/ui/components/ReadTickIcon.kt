package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 「我发的消息」已读状态图标：未读 = 单勾（13×10），已读 = 双勾（18×10）。
 * 规格见 `../IMServer/docs/design/READ_TICK_DESIGN.md`：笔画 1.5、圆头圆角；双勾 = 单勾右移 5。
 * 图本身无色，颜色全靠 tint。
 *
 * [fontSize] 是旁边时间/预览文字的字号：图高 = 字号 × 0.95，底边与文字基线对齐
 * （RowScope 扩展，用 `alignBy` 把图底边登记为基线；同一 Row 里的文字要配 `Modifier.alignByBaseline()`）。
 */
@Composable
fun RowScope.ReadTickIcon(read: Boolean, tint: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val h = with(LocalDensity.current) { (fontSize.toDp().value * 0.95f).dp }
    val w = h * (if (read) 1.8f else 1.3f)
    Icon(
        imageVector = if (read) DoubleTick else SingleTick,
        contentDescription = null,
        tint = tint,
        modifier = modifier.width(w).height(h).alignBy { it.measuredHeight },
    )
}

private fun tickVector(name: String, width: Float, strokes: List<Float>): ImageVector =
    ImageVector.Builder(name, width.dp, 10.dp, width, 10f).apply {
        for (dx in strokes) {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(1f + dx, 5.2f)
                lineTo(4.4f + dx, 8.6f)
                lineTo(12f + dx, 1f)
            }
        }
    }.build()

private val SingleTick: ImageVector by lazy { tickVector("ReadTickSingle", 13f, listOf(0f)) }
private val DoubleTick: ImageVector by lazy { tickVector("ReadTickDouble", 18f, listOf(0f, 5f)) }
