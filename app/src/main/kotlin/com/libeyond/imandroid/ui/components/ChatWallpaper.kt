package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Coffee
import com.composables.icons.lucide.Gamepad2
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Leaf
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Zap
import com.libeyond.imandroid.data.ChatWallpaper
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 聊天壁纸（对齐 iOS `IMChatBackgroundView`）：上→下渐变 + 可选涂鸦图案。
 *
 * - [ChatWallpaper.PLAIN] 只铺上端色；[ChatWallpaper.GRADIENT] 只有渐变；[ChatWallpaper.DOODLE] 渐变上再叠图案。
 * - 图案是 220dp 见方的一块平铺，十个图标的位置/大小**逐个照抄 iOS**（那边用 SF Symbols 实心图，
 *   本端用 Lucide 同义线框图——没有第二套图标库可选，线框在 α0.16 下观感足够接近）。
 * - 颜色全部从参数进来：聊天页传当前令牌，外观页的预览/缩略图传任意主题的配色，同一个组件画两处。
 *
 * 只画背景、不吃触摸；放在消息列表的**下面一层**（`Box` 的第一个孩子，`matchParentSize`）。
 */
@Composable
fun ChatWallpaperBackground(
    modifier: Modifier = Modifier,
    style: ChatWallpaper = IMTheme.appearance.wallpaper,
    top: Color = IMTheme.colors.wallpaperTop,
    bottom: Color = IMTheme.colors.wallpaperBottom,
    doodle: Color = IMTheme.colors.wallpaperDoodle,
    /** 图案整体缩放（缩略图里 0.72 倍，同 iOS 网格页的 `scale`）。 */
    patternScale: Float = 1f,
) {
    val painters = DOODLE_ICONS.map { rememberVectorPainter(it.icon) }
    val tint = ColorFilter.tint(doodle)
    Canvas(modifier) {
        if (style == ChatWallpaper.PLAIN) {
            drawRect(top)
        } else {
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
        }
        if (style != ChatWallpaper.DOODLE) return@Canvas
        val tile = TILE.toPx() * patternScale
        clipRect {
            var y = 0f
            while (y < size.height) {
                var x = 0f
                while (x < size.width) {
                    DOODLE_ICONS.forEachIndexed { i, d ->
                        val s = d.size.toPx() * patternScale
                        translate(x + d.x.toPx() * patternScale - s / 2, y + d.y.toPx() * patternScale - s / 2) {
                            with(painters[i]) { draw(Size(s, s), colorFilter = tint) }
                        }
                    }
                    x += tile
                }
                y += tile
            }
        }
    }
}

private class Doodle(val icon: ImageVector, val x: Dp, val y: Dp, val size: Dp)

private val TILE = 220.dp

/** iOS `IMChatBackgroundView` 的 `(symbol, x, y, pt)` 表，中心点坐标。 */
private val DOODLE_ICONS = listOf(
    Doodle(Lucide.Heart, 28.dp, 30.dp, 22.dp),
    Doodle(Lucide.Star, 150.dp, 18.dp, 20.dp),
    Doodle(Lucide.Gamepad2, 95.dp, 70.dp, 26.dp),
    Doodle(Lucide.Gift, 186.dp, 92.dp, 22.dp),
    Doodle(Lucide.Music, 38.dp, 110.dp, 24.dp),
    Doodle(Lucide.Leaf, 170.dp, 158.dp, 22.dp),
    Doodle(Lucide.Zap, 110.dp, 150.dp, 20.dp),
    Doodle(Lucide.Moon, 18.dp, 178.dp, 20.dp),
    Doodle(Lucide.Coffee, 70.dp, 196.dp, 22.dp),
    Doodle(Lucide.Camera, 196.dp, 196.dp, 20.dp),
)
