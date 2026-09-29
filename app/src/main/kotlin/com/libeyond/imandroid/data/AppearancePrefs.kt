package com.libeyond.imandroid.data

import com.libeyond.imandroid.ui.theme.IMThemeMode
import kotlin.math.roundToInt

/**
 * 聊天主题（对齐 iOS `IMAppearance.themeID` 的 14 个内置 ID，**顺序即界面顺序**）。
 * wire 值与 iOS NSUserDefaults 里存的字符串一致——不跨端同步，只为三端代码/文档对得上号。
 */
enum class ChatThemeId(val wire: String) {
    CLASSIC("classic"),
    OCEAN("ocean"),
    VIOLET("violet"),
    MIDNIGHT("midnight"),
    LIME("lime"),
    TITIAN("titian"),
    MARS_GREEN("mars-green"),
    KLEIN_BLUE("klein-blue"),
    BURGUNDY("burgundy"),
    SCHONBRUNN("schonbrunn"),
    TIFFANY("tiffany"),
    CHINA_RED("china-red"),
    HERMES_ORANGE("hermes-orange"),
    PRUSSIAN_BLUE("prussian-blue"),
    ;

    companion object {
        /** 未知/缺失一律回落经典（iOS 同判据）。 */
        fun fromWire(wire: String?): ChatThemeId = entries.firstOrNull { it.wire == wire } ?: CLASSIC
    }
}

/** 聊天壁纸样式（iOS `wallpaperID`）：涂鸦 = 渐变 + 图案，渐变 = 只有渐变，纯色 = 只取上端色。 */
enum class ChatWallpaper(val wire: String) {
    DOODLE("doodle"),
    GRADIENT("gradient"),
    PLAIN("plain"),
    ;

    companion object {
        fun fromWire(wire: String?): ChatWallpaper = entries.firstOrNull { it.wire == wire } ?: DOODLE
    }
}

/**
 * 外观偏好的一份快照（对齐 iOS `IMAppearance` 的六个属性）。**本机数据、不属于账号**：
 * 不上服务端、退出登录后保留（`IMServer/docs/UI_COLOR.md` §1）。
 *
 * 字号默认 15 而不是 iOS 的 17：iOS 17pt 是其系统正文基线，Android/Web 的正文基线是 15
 * （Web `--msg-font` 同默认 15），可调范围三端一致 14～22。差异登记在 `docs/UI_PARITY_IOS.md` §4.11。
 */
data class AppearancePrefs(
    val mode: IMThemeMode = IMThemeMode.System,
    val theme: ChatThemeId = ChatThemeId.CLASSIC,
    val wallpaper: ChatWallpaper = ChatWallpaper.DOODLE,
    val chatFontSize: Int = DEFAULT_FONT,
    val bubbleRadius: Int = DEFAULT_RADIUS,
    val animationsEnabled: Boolean = true,
) {
    /** 写入前统一过这一道：数值夹到合法区间，与 iOS setter 的 MIN/MAX 同口径。 */
    fun clamped(): AppearancePrefs = copy(
        chatFontSize = chatFontSize.coerceIn(FONT_MIN, FONT_MAX),
        bubbleRadius = bubbleRadius.coerceIn(RADIUS_MIN, RADIUS_MAX),
    )

    /** 与「还原」后的出厂值一致时为 true（本端据此把「还原」置灰，而不是点了没反应）。 */
    val isDefault: Boolean get() = this == AppearancePrefs()

    /** 落盘成字符串键值对（SharedPreferences 与单测的内存表共用这一套编码）。 */
    fun encode(): Map<String, String> = mapOf(
        KEY_MODE to mode.ordinal.toString(),
        KEY_THEME to theme.wire,
        KEY_WALLPAPER to wallpaper.wire,
        KEY_FONT to chatFontSize.toString(),
        KEY_RADIUS to bubbleRadius.toString(),
        KEY_ANIMATIONS to animationsEnabled.toString(),
    )

    companion object {
        const val FONT_MIN = 14
        const val FONT_MAX = 22
        const val DEFAULT_FONT = 15
        const val RADIUS_MIN = 6
        const val RADIUS_MAX = 24
        const val DEFAULT_RADIUS = 18

        // 键名照抄 iOS（去掉 `im.appearance.` 前缀，因为本端每类偏好一个独立 prefs 文件）
        const val KEY_MODE = "mode"
        const val KEY_THEME = "theme"
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_FONT = "chatFont"
        const val KEY_RADIUS = "bubbleRadius"
        const val KEY_ANIMATIONS = "animations"

        /**
         * 从存储读回（纯函数，单测钉住）。每个键独立兜底：一个键坏了不连累其它键。
         * 模式按 iOS 口径**夹紧**而不是回落（存了 99 → 深色，iOS `testUnknownIdentifiersFallBackToDefaults` 同）。
         */
        fun decode(get: (String) -> String?): AppearancePrefs {
            val d = AppearancePrefs()
            val modeIndex = get(KEY_MODE)?.toIntOrNull()
            return AppearancePrefs(
                mode = modeIndex?.let { IMThemeMode.entries[it.coerceIn(0, IMThemeMode.entries.size - 1)] } ?: d.mode,
                theme = ChatThemeId.fromWire(get(KEY_THEME)),
                wallpaper = ChatWallpaper.fromWire(get(KEY_WALLPAPER)),
                // iOS 存的是 double；本端存整数，但读时容忍小数（手工改过或将来换存法都不炸）
                chatFontSize = get(KEY_FONT)?.toDoubleOrNull()?.roundToInt() ?: d.chatFontSize,
                bubbleRadius = get(KEY_RADIUS)?.toDoubleOrNull()?.roundToInt() ?: d.bubbleRadius,
                animationsEnabled = get(KEY_ANIMATIONS)?.toBooleanStrictOrNull() ?: d.animationsEnabled,
            ).clamped()
        }
    }
}
