package com.libeyond.imandroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 语义令牌（design tokens）——对应 iOS `IMTheme` 与 Web `:root` CSS 变量。
 *
 * 取值**逐条抄自** `../IMServer/docs/UI_COLOR.md` §2 总表与 `../im-web/src/styles.css`，
 * 不是本端另调的一套。改颜色 = 改那张总表，然后三端同步，别只改这里。
 *
 * 三条硬规则（UI_COLOR.md §1）：
 *  1. 业务代码只用语义令牌，禁止散落 Hex/RGB；缺语义先在这里扩展，不得就地写颜色。
 *  2. 浅色/深色/跟随系统共用同一套令牌名，只替换取值。
 *  3. 新增外观项必须同时处理：持久化、实时生效、重启恢复、浅色取值、深色取值。
 *
 * 白色只允许用于有确定深色底的图标/头像文字/媒体遮罩；黑色只允许用于遮罩与阴影。
 */
data class IMColors(
    // --- 品牌 / 强调 ---
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,

    // --- 背景层级（页面 → 分组 → 卡片 → 表面 → 浮层，至少三层可辨，§6.2）---
    val pageBackground: Color,
    val groupedBackground: Color,
    val cardBackground: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHover: Color,
    val selectionBackground: Color,

    // --- 文本三级 ---
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    // --- 线条 / 状态 ---
    val separator: Color,
    val danger: Color,
    val dangerSoft: Color,
    val online: Color,
    val link: Color,
    val unreadBadge: Color,

    // --- 聊天 ---
    val bubbleMe: Color,
    val bubbleThem: Color,
    val checkRead: Color,
    val metaTime: Color,
    val wallpaperTop: Color,
    val wallpaperBottom: Color,
    val datePillBackground: Color,
    /** 群系统消息里的人名段。刻意不用 accent：胶囊底就是主题绿，绿字绿底看不出哪几个字是名字。 */
    val sysName: Color,

    // --- 媒体上的元素（底恒为暗遮罩，故不随明暗切换）---
    val onMedia: Color,
    val onMediaMuted: Color,
    val checkReadOnMedia: Color,

    // --- 遮罩 / 阴影 / 中性填充 ---
    val overlay: Color,
    val overlayStrong: Color,
    val shadowSoft: Color,
    val shadowStrong: Color,
    val subtleFill: Color,
    val neutralControl: Color,
    val avatarRing: Color,

    /** 是否深色取值——只用于必须分叉的少数场合（如选图标资源），别拿它到处写 if。 */
    val isDark: Boolean,
)

/** 浅色取值（对齐 im-web `:root`）。 */
val LightIMColors = IMColors(
    accent = Color(0xFF4CA64C),
    accentSoft = Color(0x1F4CA64C),
    onAccent = Color(0xFFFFFFFF),

    pageBackground = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF5F6F8),
    cardBackground = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F8FA),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceHover = Color(0xFFEEF1F4),
    selectionBackground = Color(0x3D4CA64C),

    textPrimary = Color(0xFF1D2129),
    textSecondary = Color(0xFF8A929C),
    textTertiary = Color(0xFFAEB4BC),

    separator = Color(0xFFE3E6EA),
    danger = Color(0xFFE5484D),
    dangerSoft = Color(0x1FE5484D),
    online = Color(0xFF1AAB5B),
    link = Color(0xFF2477D4),
    unreadBadge = Color(0xFF3E91FF),

    bubbleMe = Color(0xFFE3FDD0),
    bubbleThem = Color(0xFFFFFFFF),
    checkRead = Color(0xFF4CA64C),
    metaTime = Color(0xFF6B8A5E),
    wallpaperTop = Color(0xFFD6E8C4),
    wallpaperBottom = Color(0xFFB4D89B),
    datePillBackground = Color(0x8C5C8A4C),
    sysName = Color(0xFFFFD98A),

    onMedia = Color(0xFFFFFFFF),
    onMediaMuted = Color(0x59FFFFFF),
    checkReadOnMedia = Color(0xFF7DDC7D),

    overlay = Color(0x66000000),
    overlayStrong = Color(0xB8000000),
    shadowSoft = Color(0x2E000000),
    shadowStrong = Color(0x47000000),
    subtleFill = Color(0x0D1D2129),
    neutralControl = Color(0x2E7F7F7F),
    avatarRing = Color(0x381D2129),

    isDark = false,
)

/** 深色取值（对齐 im-web `prefers-color-scheme: dark`）。 */
val DarkIMColors = IMColors(
    accent = Color(0xFF4CA64C),
    accentSoft = Color(0x1F4CA64C),
    onAccent = Color(0xFFFFFFFF),

    pageBackground = Color(0xFF242424),
    groupedBackground = Color(0xFF1F1F1F),
    cardBackground = Color(0xFF2C2C2C),
    surface = Color(0xFF323232),
    surfaceElevated = Color(0xFF383838),
    surfaceHover = Color(0xFF404040),
    selectionBackground = Color(0x5257BC62),

    textPrimary = Color(0xFFE6E8EB),
    textSecondary = Color(0xFFA0A7B0),
    textTertiary = Color(0xFF707883),

    separator = Color(0xFF454545),
    danger = Color(0xFFFF696E),
    dangerSoft = Color(0x1FFF696E),
    online = Color(0xFF42C77A),
    link = Color(0xFF66A9FF),
    unreadBadge = Color(0xFF3E91FF),

    bubbleMe = Color(0xFF1F4D2E),
    bubbleThem = Color(0xFF262D31),
    checkRead = Color(0xFF7DDC7D),
    metaTime = Color(0xFF9FB89A),
    wallpaperTop = Color(0xFF0E1A12),
    wallpaperBottom = Color(0xFF16261A),
    datePillBackground = Color(0x73000000),
    sysName = Color(0xFFFFD98A),

    onMedia = Color(0xFFFFFFFF),
    onMediaMuted = Color(0x59FFFFFF),
    checkReadOnMedia = Color(0xFF7DDC7D),

    overlay = Color(0x66000000),
    overlayStrong = Color(0xB8000000),
    shadowSoft = Color(0x2E000000),
    shadowStrong = Color(0x47000000),
    subtleFill = Color(0x12FFFFFF),
    neutralControl = Color(0x4D7F7F7F),
    avatarRing = Color(0x52FFFFFF),

    isDark = true,
)

/** 圆角、间距等尺寸令牌（UI_COLOR.md §4）。 */
data class IMDimens(
    val radiusCard: Dp = 14.dp,
    val radiusBubble: Dp = 18.dp,
    val radiusMenu: Dp = 8.dp,
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    /** 页面左右边距，§4 规定为 16。 */
    val space4: Dp = 16.dp,
    /** 同级卡片间距 ≥12；外观设置里各卡间距 ≥24。 */
    val cardGap: Dp = 12.dp,
    val sectionGap: Dp = 24.dp,
)

/**
 * 用户可调的外观值（对应 iOS `IMAppearance` / Web `--msg-font`、`--radius-bubble`）。
 * 这些**不是常量**，来自外观偏好层；页面不得直接读写本地存储（§7.3）。
 */
data class IMAppearance(
    /** 聊天正文字号，用户可调 14～22（§3）。 */
    val chatFontSize: TextUnit = 15.sp,
    /** 气泡圆角，用户可调 6～24（§2）。 */
    val bubbleRadius: Dp = 18.dp,
) {
    /** 系统提示/居中时间标签随消息字号等比缩放（×0.8），与 Web `--sys-font` 同口径。 */
    val sysFontSize: TextUnit get() = (chatFontSize.value * 0.8f).sp

    companion object {
        const val CHAT_FONT_MIN = 14f
        const val CHAT_FONT_MAX = 22f
        const val BUBBLE_RADIUS_MIN = 6f
        const val BUBBLE_RADIUS_MAX = 24f
    }
}
