package com.libeyond.imandroid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 「定位到聊天位置」共用图标（PINNED_PANEL_DESIGN §8）：聊天气泡轮廓 + 气泡内斜向箭头 ↗。
 * 24×24 viewBox、笔画 1.8、圆头圆角；**无色图形**，颜色靠调用处 tint（`Icon(tint=)` / `ColorFilter.tint`）。
 * 路径即真相，与 iOS `UIBezierPath` / Web 内联 SVG 同一份——改路径三端一起改。本期只用于置顶面板。
 */
val LocateInChatIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LocateInChat", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        LOCATE_PATHS.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                // 占位色：渲染时被 tint 整体替换
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

private val LOCATE_PATHS = listOf(
    "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-6.2L8 20v-3.5H6A2.5 2.5 0 0 1 3.5 14V7A2.5 2.5 0 0 1 6 4.5z",
    "M9.5 13l5-5M10.5 8h4v4",
)
