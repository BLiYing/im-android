package com.libeyond.imandroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** 显示模式：跟随系统 / 浅色 / 深色。默认跟随系统（UI_COLOR.md §1.2）。 */
enum class IMThemeMode { System, Light, Dark }

private val LocalIMColors = staticCompositionLocalOf { LightIMColors }
private val LocalIMDimens = staticCompositionLocalOf { IMDimens() }
private val LocalIMAppearance = staticCompositionLocalOf { IMAppearance() }

/**
 * 令牌取用入口——业务代码一律 `IMTheme.colors.textSecondary`，不写 Hex。
 * 与 iOS 的 `IMTheme` 同名同职责，便于三端对照。
 */
object IMTheme {
    val colors: IMColors
        @Composable @ReadOnlyComposable get() = LocalIMColors.current

    val dimens: IMDimens
        @Composable @ReadOnlyComposable get() = LocalIMDimens.current

    /** 用户可调的字号/圆角。 */
    val appearance: IMAppearance
        @Composable @ReadOnlyComposable get() = LocalIMAppearance.current
}

/**
 * 应用主题外壳。
 *
 * 刻意**不启用 Material You 动态取色**：三端要的是同一套 Telegram 绿，
 * 让系统壁纸决定主色会使 Android 与 iOS/Web 当场分叉（UI_COLOR.md §1.1）。
 *
 * 同时把令牌喂给 Material3 的 ColorScheme，好让直接用的 M3 组件
 * （Button/TextField/Switch…）也落在同一套色上，而不是 M3 默认紫。
 */
@Composable
fun IMAppTheme(
    mode: IMThemeMode = IMThemeMode.System,
    appearance: IMAppearance = IMAppearance(),
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        IMThemeMode.System -> isSystemInDarkTheme()
        IMThemeMode.Light -> false
        IMThemeMode.Dark -> true
    }
    val colors = if (dark) DarkIMColors else LightIMColors

    val m3 = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.pageBackground,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            error = colors.danger,
            outline = colors.separator,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.pageBackground,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            error = colors.danger,
            outline = colors.separator,
        )
    }

    CompositionLocalProvider(
        LocalIMColors provides colors,
        LocalIMDimens provides IMDimens(),
        LocalIMAppearance provides appearance,
    ) {
        MaterialTheme(colorScheme = m3, typography = IMTypography, content = content)
    }
}
