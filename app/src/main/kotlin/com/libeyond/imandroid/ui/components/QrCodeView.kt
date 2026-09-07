package com.libeyond.imandroid.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.libeyond.imandroid.data.QrMatrix
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlin.math.floor

/**
 * 二维码画布。
 *
 * **恒黑码 + 白底**，不随深色模式反色：反色的码有相当多扫码器认不出
 * （它们按「暗模块在亮底上」找定位图案）。这是二维码的功能约束，不是没跟上主题
 * ——所以这里的白/黑是 Tokens 顶部允许的例外之一（有确定底色的图形），
 * 且卡片本身也画成白底，视觉上是「一张纸」，深色模式下也自洽。
 */
@Composable
fun QrCodeView(matrix: QrMatrix?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            Text(
                "二维码加载中…",
                color = IMTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(size * QUIET_RATIO)) {
            // **按整数像素取模块边长**：直接用 size/模块数 会得到小数，相邻模块因四舍五入
            // 出现 1px 缝或叠边，扫码器对这种毛刺很敏感。宁可整体略小一点、边缘留白多一点。
            val cell = floor(this.size.minDimension / matrix.size)
            if (cell < 1f) return@Canvas
            val drawn = cell * matrix.size
            val originX = (this.size.width - drawn) / 2f
            val originY = (this.size.height - drawn) / 2f
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (!matrix.isDark(x, y)) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(originX + x * cell, originY + y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

/** 静默区占整张图的比例（每边）。低于 ~4 个模块的留白会明显掉识别率。 */
private const val QUIET_RATIO = 0.06f

/**
 * 把矩阵渲染成位图（保存到相册 / 分享用）。
 *
 * 与 [QrCodeView] 分开画而不是截屏 Composable：截屏拿到的是屏幕密度下的像素，
 * 在低分屏上存出来的图糊到扫不出来。这里按模块数取整倍放大，任何设备上都是清晰的。
 */
fun qrToBitmap(matrix: QrMatrix, targetPx: Int = 1024): Bitmap {
    val quiet = 4 // 规范建议的静默区宽度（模块数）
    val total = matrix.size + quiet * 2
    val scale = (targetPx / total).coerceAtLeast(1)
    val side = total * scale
    val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(android.graphics.Color.WHITE)
    val pixels = IntArray(side * side) { android.graphics.Color.WHITE }
    for (y in 0 until matrix.size) {
        for (x in 0 until matrix.size) {
            if (!matrix.isDark(x, y)) continue
            val px = (x + quiet) * scale
            val py = (y + quiet) * scale
            for (dy in 0 until scale) {
                val row = (py + dy) * side
                for (dx in 0 until scale) {
                    pixels[row + px + dx] = android.graphics.Color.BLACK
                }
            }
        }
    }
    bmp.setPixels(pixels, 0, side, 0, 0, side, side)
    return bmp
}
