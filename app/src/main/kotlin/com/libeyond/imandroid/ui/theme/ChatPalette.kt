package com.libeyond.imandroid.ui.theme

import com.libeyond.imandroid.data.ChatThemeId
import kotlin.math.roundToInt

/**
 * 一个聊天主题在某一明暗下的四个颜色（ARGB Int，纯 Kotlin、可单测）。
 *
 * 主题**只管这四样**：强调色、我方气泡、壁纸上下两端（`IMServer/docs/UI_COLOR.md` §5）——
 * 对方气泡、日期胶囊、已读勾等保持中性，壁纸色也不许漏到别的组件上。
 */
data class ChatPalette(val accent: Int, val bubbleMe: Int, val wallpaperTop: Int, val wallpaperBottom: Int)

/**
 * 主题 → 配色，**逐值照抄 iOS `IMAppearance.m`**（`accentColorForThemeID:` /
 * `bubbleMeColorForThemeID:` / `wallpaperPairForThemeID:dark:`）：
 *  - 经典/海洋/紫晶/深海四个老主题是手调值；
 *  - 其余十个只给强调色，气泡与壁纸按 [blend] 派生（浅色混白、深色混黑）。
 *
 * 经典主题例外：返回本端令牌表里的原值（[LightIMColors]/[DarkIMColors]），它本来就是三端
 * 对齐过的 Telegram 绿，与 iOS 经典只差系统绿与 `#4CA64C` 的那一点——不为了「照抄」把
 * 全 App 默认强调色悄悄换掉。
 */
object ChatPalettes {

    fun of(theme: ChatThemeId, dark: Boolean): ChatPalette = when (theme) {
        ChatThemeId.CLASSIC -> if (dark) {
            ChatPalette(0xFF4CA64C.toInt(), 0xFF1F4D2E.toInt(), 0xFF0E1A12.toInt(), 0xFF16261A.toInt())
        } else {
            ChatPalette(0xFF4CA64C.toInt(), 0xFFE3FDD0.toInt(), 0xFFD6E8C4.toInt(), 0xFFB4D89B.toInt())
        }
        // iOS systemBlue / systemPurple 的浅深两值；老主题的气泡与壁纸是 iOS 里的 0～1 浮点，按 ×255 四舍五入
        ChatThemeId.OCEAN -> legacy(
            dark, accentLight = 0x007AFF, accentDark = 0x0A84FF,
            meLight = rgbf(0.78, 0.92, 1.0), meDark = rgbf(0.08, 0.27, 0.43),
            wallLight = rgbf(0.72, 0.91, 0.98) to rgbf(0.58, 0.78, 0.94),
            wallDark = rgbf(0.04, 0.12, 0.20) to rgbf(0.07, 0.24, 0.32),
        )
        ChatThemeId.VIOLET -> legacy(
            dark, accentLight = 0xAF52DE, accentDark = 0xBF5AF2,
            meLight = rgbf(0.91, 0.84, 1.0), meDark = rgbf(0.28, 0.18, 0.42),
            wallLight = rgbf(0.88, 0.79, 0.98) to rgbf(0.72, 0.86, 0.98),
            wallDark = rgbf(0.12, 0.07, 0.22) to rgbf(0.25, 0.12, 0.32),
        )
        ChatThemeId.MIDNIGHT -> legacy(
            dark, accentLight = rgbf(0.24, 0.62, 0.88), accentDark = rgbf(0.24, 0.62, 0.88),
            meLight = rgbf(0.78, 0.91, 0.95), meDark = rgbf(0.10, 0.24, 0.34),
            wallLight = rgbf(0.75, 0.84, 0.89) to rgbf(0.56, 0.72, 0.81),
            wallDark = rgbf(0.02, 0.05, 0.10) to rgbf(0.05, 0.13, 0.20),
        )
        else -> derived(DERIVED_ACCENTS.getValue(theme), dark)
    }

    /** iOS `IMCustomThemeAccents()`：十个派生主题只定义强调色。 */
    private val DERIVED_ACCENTS: Map<ChatThemeId, Int> = mapOf(
        ChatThemeId.LIME to 0x6ECC54,
        ChatThemeId.TITIAN to 0xD34947,
        ChatThemeId.MARS_GREEN to 0x018B8D,
        ChatThemeId.KLEIN_BLUE to 0x002FA7,
        ChatThemeId.BURGUNDY to 0x470125,
        ChatThemeId.SCHONBRUNN to 0xF9D46C,
        ChatThemeId.TIFFANY to 0x71E2D1,
        ChatThemeId.CHINA_RED to 0xC8161D,
        ChatThemeId.HERMES_ORANGE to 0xEB5C20,
        ChatThemeId.PRUSSIAN_BLUE to 0x0D3A69,
    )

    private const val WHITE = 0xFFFFFF
    private const val BLACK = 0x000000

    /** 派生规则（iOS 注释原意）：气泡保留较多本色，壁纸压到极淡，两者拉开区分度。 */
    private fun derived(accent: Int, dark: Boolean): ChatPalette = if (dark) {
        ChatPalette(opaque(accent), blend(accent, BLACK, 0.50), blend(accent, BLACK, 0.92), blend(accent, BLACK, 0.84))
    } else {
        ChatPalette(opaque(accent), blend(accent, WHITE, 0.58), blend(accent, WHITE, 0.91), blend(accent, WHITE, 0.84))
    }

    @Suppress("LongParameterList")
    private fun legacy(
        dark: Boolean,
        accentLight: Int,
        accentDark: Int,
        meLight: Int,
        meDark: Int,
        wallLight: Pair<Int, Int>,
        wallDark: Pair<Int, Int>,
    ): ChatPalette = if (dark) {
        ChatPalette(opaque(accentDark), opaque(meDark), opaque(wallDark.first), opaque(wallDark.second))
    } else {
        ChatPalette(opaque(accentLight), opaque(meLight), opaque(wallLight.first), opaque(wallLight.second))
    }

    /** iOS `IMBlendColor`：把 [rgb] 按比例 [f] 混向 [target]（0 = 原色，1 = 目标色），结果不透明。 */
    fun blend(rgb: Int, target: Int, f: Double): Int {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        fun mix(shift: Int): Int {
            val a = ch(rgb, shift)
            return (a + (ch(target, shift) - a) * f).roundToInt().coerceIn(0, 255)
        }
        return opaque((mix(16) shl 16) or (mix(8) shl 8) or mix(0))
    }

    private fun rgbf(r: Double, g: Double, b: Double): Int =
        ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()

    private fun opaque(rgb: Int): Int = rgb or (0xFF shl 24)
}
