package com.libeyond.imandroid.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.libeyond.imandroid.ui.theme.IMTheme

// 「定位到某条」的高亮：只盖在气泡 / 宫格上，不是整行（iOS `flashRowAtIndexPath:` ——
// 在气泡 previewTargetView 上盖一层 accent α0.35 遮罩再淡出）。
// 用 CompositionLocal 而不是层层加参数：列表行外壳（ChatListItem）只对命中的那一行提供 true，
// 气泡 / 宫格各自在自己的 Box 上取用。

/** 本行是否正被「定位」高亮。默认 false（长按预览等重绘处不会带色，对应 iOS 光栅化预览时隐藏蒙层）。 */
internal val LocalLocateFlash = compositionLocalOf { false }

/** iOS 遮罩强度。 */
private const val FLASH_ALPHA = 0.35f

/** 亮得快、退得慢（iOS：停 0.3s 后 0.9s 淡出）。 */
@Composable
internal fun Modifier.locateFlash(): Modifier {
    val on = LocalLocateFlash.current
    val a by animateFloatAsState(
        targetValue = if (on) FLASH_ALPHA else 0f,
        animationSpec = if (on) tween(120) else tween(900),
        label = "locateFlash",
    )
    if (a <= 0f) return this
    val color = IMTheme.colors.accent
    return drawWithContent {
        drawContent()
        drawRect(color.copy(alpha = a))
    }
}
