package com.libeyond.mediapicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主题接缝：本模块**不认识** app 的 `IMTheme`，颜色与间距由调用方注入。
 *
 * 这是「依赖方向单向」的代价，也是它的价值——模块不认识业务主题，所以换个 App 能直接用；
 * 反过来若模块 `import IMTheme`，依赖就成了双向，模块也就名存实亡了。
 *
 * 给了一套中性默认值，**只为让 `@Preview` 和单测能跑**，正式使用一律注入。
 */
data class MediaPickerSkin(
    val accent: Color = Color(0xFF4CAF50),
    val onAccent: Color = Color.White,
    val pageBackground: Color = Color(0xFF121212),
    val surface: Color = Color(0xFF1E1E1E),
    val surfaceElevated: Color = Color(0xFF2A2A2A),
    val textPrimary: Color = Color(0xFFEDEDED),
    val textSecondary: Color = Color(0xFF9E9E9E),
    /** 半透明遮罩：选中格压暗、浮层背景。 */
    val overlay: Color = Color(0x66000000),
    /** 缩略图未加载时的底色。 */
    val subtleFill: Color = Color(0xFF2C2C2C),
    val separator: Color = Color(0x1FFFFFFF),
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val radiusCard: Dp = 14.dp,
)

val LocalMediaPickerSkin: ProvidableCompositionLocal<MediaPickerSkin> =
    staticCompositionLocalOf { MediaPickerSkin() }

/** 在这层里渲染选择器，内部所有颜色取 [skin]。 */
@Composable
fun MediaPickerTheme(skin: MediaPickerSkin, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMediaPickerSkin provides skin, content = content)
}
