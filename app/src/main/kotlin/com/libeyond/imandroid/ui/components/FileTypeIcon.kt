package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.FileTypeIcons

/** iOS 那套 SVG 的 viewBox 是 120×128，所有坐标都按它写，画的时候整体缩放。 */
private const val VB_W = 120f
private const val VB_H = 128f

/** 页面外形（带折角）——**逐字取自 iOS `FileType_*.svg`**，不是自己描的。 */
private const val PAGE_PATH =
    "M27 6h48l31 31v70c0 9-7 15-15 15H27c-9 0-15-7-15-15V21c0-8 7-15 15-15z"

/** 右上折角高光。 */
private const val FOLD_PATH = "M75 6v20c0 7 5 12 12 12h19z"

/**
 * 文件类型图标。
 *
 * **同一套设计，Android 侧重画**：iOS 的 22 张是 SVG，而 Android 的 vector drawable
 * 不支持 `<text>`，那些图的角标（PDF / W / X / `{ }` / `</>`）全是文字元素，直接转会丢光。
 * 于是这里按同样的页面外形、同样的渐变色、同样的角标在 Compose 里画
 * （路径数据逐字取自 iOS 的 SVG，配色见 [FileTypeIcons.gradient]）。
 * 差异登记见 `docs/UI_PARITY_IOS.md` §3。
 */
@Composable
fun FileTypeIcon(fileName: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val kind = remember(fileName) { FileTypeIcons.kindFor(fileName) }
    val (c0, c1) = remember(kind) { FileTypeIcons.gradient(kind) }
    val label = remember(kind) { FileTypeIcons.label(kind) }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sx = this.size.width / VB_W
            val sy = this.size.height / VB_H
            val page = scaledPath(PAGE_PATH, sx, sy)
            drawPath(
                page,
                Brush.linearGradient(
                    listOf(Color(c0), Color(c1)),
                    start = Offset(20 * sx, 8 * sy),
                    end = Offset(100 * sx, 122 * sy),
                ),
            )
            // 描边：白 24% —— iOS 用它把图标从深色底上勾出来
            drawPath(page, Color.White.copy(alpha = 0.24f), style = Stroke(width = 2 * sx))
            drawPath(scaledPath(FOLD_PATH, sx, sy), Color.White.copy(alpha = 0.34f))

            if (label.isEmpty()) drawKindGlyph(kind, sx, sy)
        }
        if (label.isNotEmpty()) {
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                // 角标随图标尺寸缩放；「PDF」「PPT」三个字母时收窄一档，免得顶到边
                fontSize = (size.value * if (label.length >= 3) 0.24f else 0.34f).sp,
                modifier = Modifier.offset(y = size * 0.10f),
            )
        }
    }
}

/**
 * 把 SVG path 按 viewBox 缩放到画布尺寸。
 *
 * 用 Compose 自己的 `Matrix` + `Path.transform`，不下潜到 `android.graphics`——
 * 后者要处理 `asAndroidPath()` 的可空与所有权，得不偿失。
 */
private fun scaledPath(data: String, sx: Float, sy: Float): Path {
    val p = PathParser().parsePathString(data).toPath()
    p.transform(androidx.compose.ui.graphics.Matrix().apply { scale(sx, sy) })
    return p
}

/**
 * 没有角标文字的那几类，iOS 画的是白色图形。**这里逐条照着 SVG 里的那几笔重画**：
 * archive=三个方块+锁扣、audio=五根音柱、image=一轮太阳+山、video=播放三角、
 * 其余（csv/pages/numbers/keynote/text/xml/database/ebook）=四条横线。
 */
private fun DrawScope.drawKindGlyph(kind: String, sx: Float, sy: Float) {
    val w = Color.White
    when (kind) {
        "video" -> drawPath(scaledPath("m48 47 34 23-34 23z", sx, sy), w)
        "image" -> {
            drawCircle(w, radius = 7 * sx, center = Offset(42 * sx, 54 * sy))
            drawPath(scaledPath("m29 94 22-28 13 15 9-11 19 24z", sx, sy), w)
        }
        "audio" -> {
            val bars = listOf(34f to (68f to 76f), 46f to (58f to 86f), 58f to (48f to 96f),
                70f to (57f to 87f), 82f to (64f to 80f))
            bars.forEach { (x, yy) ->
                drawLine(w, Offset(x * sx, yy.first * sy), Offset(x * sx, yy.second * sy),
                    strokeWidth = 6 * sx, cap = StrokeCap.Round)
            }
        }
        "archive" -> {
            listOf(42f, 54f, 66f).forEach { y ->
                drawRoundRect(w, topLeft = Offset(56 * sx, y * sy),
                    size = Size(10 * sx, 9 * sy),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * sx))
            }
            drawRoundRect(w, topLeft = Offset(53 * sx, 78 * sy),
                size = Size(16 * sx, 18 * sy),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5 * sx))
        }
        else -> {
            // 四条横线（iOS 的 text/csv/xml… 都是这个）
            listOf(51f to 86f, 66f to 76f, 81f to 82f, 96f to 68f).forEach { (y, x2) ->
                drawLine(w, Offset(34 * sx, y * sy), Offset(x2 * sx, y * sy),
                    strokeWidth = 6 * sx, cap = StrokeCap.Round)
            }
        }
    }
}
