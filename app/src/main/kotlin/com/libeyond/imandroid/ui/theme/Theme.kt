package com.libeyond.imandroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.libeyond.imandroid.data.ChatThemeId

/**
 * 媒体地址补全用的服务器地址（`host:port` + 是否 TLS）。
 *
 * 用 CompositionLocal 而不是逐层传参：头像出现在会话列表、通讯录、聊天气泡、
 * 资料页……每处都把 host 穿一遍，加一个页面就漏一个。
 * 它是**稳定值**（只在用户改服务器地址时变），放 CompositionLocal 不会引起
 * 高频重组——高频变化的 state 才不该进（CODING_STYLE §7）。
 */
data class MediaHost(val host: String = "", val useTls: Boolean = false)

val LocalMediaHost = staticCompositionLocalOf { MediaHost() }

/** 显示模式：跟随系统 / 浅色 / 深色。默认跟随系统（UI_COLOR.md §1.2）。 */
enum class IMThemeMode { System, Light, Dark }

private val LocalIMColors = staticCompositionLocalOf { LightIMColors }
private val LocalIMDimens = staticCompositionLocalOf { IMDimens() }
// 外观值会被滑块逐档改写（字号/圆角拖动即生效）：用 compositionLocalOf 只重组读它的地方；
// static 版每改一档就把整棵树重组一遍（/code-review 2026-09-29）。颜色令牌很少变，仍用 static。
private val LocalIMAppearance = compositionLocalOf { IMAppearance() }
private val LocalSettingsIconColors = staticCompositionLocalOf { LightSettingsIconColors }

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

    /** 「我」页设置行的图标底色（对齐 iOS system* 色）。 */
    val settingsIcons: IMSettingsIconColors
        @Composable @ReadOnlyComposable get() = LocalSettingsIconColors.current
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
    chatTheme: ChatThemeId = ChatThemeId.CLASSIC,
    content: @Composable () -> Unit,
) {
    val dark = isDarkFor(mode)
    val colors = themedColors(if (dark) DarkIMColors else LightIMColors, chatTheme, dark)

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
        LocalSettingsIconColors provides if (dark) DarkSettingsIconColors else LightSettingsIconColors,
    ) {
        MaterialTheme(colorScheme = m3, typography = IMTypography, content = content)
    }
}

/** 显示模式 → 此刻是否深色。`MainActivity` 用同一判据给系统栏图标选色，两边不会各算各的。 */
@Composable
fun isDarkFor(mode: IMThemeMode): Boolean = when (mode) {
    IMThemeMode.System -> isSystemInDarkTheme()
    IMThemeMode.Light -> false
    IMThemeMode.Dark -> true
}

/**
 * 把聊天主题叠到基础令牌上：**只替换**强调色、我方气泡、壁纸两端（UI_COLOR §5），
 * 其余令牌（对方气泡、日期胶囊、已读勾……）保持中性。`accentSoft` 随强调色走 α12%（iOS 同）。
 */
fun themedColors(base: IMColors, theme: ChatThemeId, dark: Boolean): IMColors {
    if (theme == ChatThemeId.CLASSIC) return base
    val p = ChatPalettes.of(theme, dark)
    val accent = Color(p.accent)
    return base.copy(
        accent = accent,
        accentSoft = accent.copy(alpha = 0.12f),
        bubbleMe = Color(p.bubbleMe),
        wallpaperTop = Color(p.wallpaperTop),
        wallpaperBottom = Color(p.wallpaperBottom),
    )
}
