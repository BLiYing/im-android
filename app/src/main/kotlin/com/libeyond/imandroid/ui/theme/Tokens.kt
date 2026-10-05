package com.libeyond.imandroid.ui.theme

import androidx.compose.ui.graphics.Color
import com.libeyond.imandroid.data.ChatWallpaper
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
    /** 已读双勾图标色（蓝，复用 link 色系；浅 #2477d4 / 深 #66a9ff）。 */
    val checkRead: Color,
    val metaTime: Color,
    val wallpaperTop: Color,
    val wallpaperBottom: Color,
    /** 涂鸦壁纸的图案色（白 α0.16 / 深色 α0.035，iOS `wallpaperDoodleColor` 同值）。 */
    val wallpaperDoodle: Color,
    val datePillBackground: Color,
    /** 群系统消息里的人名段。刻意不用 accent：胶囊底就是主题绿，绿字绿底看不出哪几个字是名字。 */
    val sysName: Color,

    // --- 媒体上的元素（底恒为暗遮罩，故不随明暗切换）---
    val onMedia: Color,
    val onMediaMuted: Color,
    /** 压在图/视频角标上的已读双勾色（蓝 #66a9ff）。 */
    val checkReadOnMedia: Color,

    // --- 遮罩 / 阴影 / 中性填充 ---
    val overlay: Color,
    val overlayStrong: Color,
    val shadowSoft: Color,
    val shadowStrong: Color,
    val subtleFill: Color,
    val neutralControl: Color,
    /**
     * 左滑动作格的**中性底**（「拉黑」）。
     *
     * 刻意**不复用** [neutralControl]：那是禁用态按钮的半透明填充（浅色 α≈18%），
     * 当动作格底色用时白字压上去对比度只有 1.2:1，浅色模式下基本看不见
     * （2026-09-09 `/code-review` 抓出）。对齐 iOS 的 `UIColor.systemGrayColor` —— **不透明**。
     */
    val swipeNeutral: Color,
    /** 左滑动作格的**正向底**（「解除拉黑」）。对齐 iOS 的 `UIColor.systemGreenColor`。 */
    val swipePositive: Color,
    val avatarRing: Color,

    // --- 语音气泡 / 迷你播放器（VOICE_MESSAGE_DESIGN §6.1，对齐 iOS `IMVoiceBubbleCell` 取色）---
    /** 己方气泡上的播放键底：浅绿气泡上要压得住的深绿（iOS 同值）；对方气泡上用 [accent]。 */
    val voicePlayMine: Color,
    /** 对方气泡波形**未播放段**：次要文字色 α0.28（iOS 曾 0.45 偏深、扫过看不出，2026-08-27 修）。 */
    val voiceWaveInactive: Color,
    /** 己方气泡波形未播放段：正文色 α0.32（已播放段直接用 [textPrimary]）。 */
    val voiceWaveInactiveMine: Color,
    /** 倍速胶囊底：强调色 α0.14（对方）/ 正文色 α0.14（己方），与波形同一取色逻辑。 */
    val voiceSpeedBg: Color,
    val voiceSpeedBgMine: Color,

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
    checkRead = Color(0xFF2477D4),
    metaTime = Color(0xFF6B8A5E),
    wallpaperTop = Color(0xFFD6E8C4),
    wallpaperBottom = Color(0xFFB4D89B),
    wallpaperDoodle = Color(0x29FFFFFF),
    datePillBackground = Color(0x8C5C8A4C),
    sysName = Color(0xFFFFD98A),

    onMedia = Color(0xFFFFFFFF),
    onMediaMuted = Color(0x59FFFFFF),
    checkReadOnMedia = Color(0xFF66A9FF),

    overlay = Color(0x66000000),
    overlayStrong = Color(0xB8000000),
    shadowSoft = Color(0x2E000000),
    shadowStrong = Color(0x47000000),
    subtleFill = Color(0x0D1D2129),
    neutralControl = Color(0x2E7F7F7F),
    swipeNeutral = Color(0xFF8E8E93),
    swipePositive = Color(0xFF34C759),
    avatarRing = Color(0x381D2129),
    voicePlayMine = Color(0xFF1F7A2E),
    voiceWaveInactive = Color(0x478A929C),
    voiceWaveInactiveMine = Color(0x521D2129),
    voiceSpeedBg = Color(0x244CA64C),
    voiceSpeedBgMine = Color(0x241D2129),

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
    checkRead = Color(0xFF66A9FF),
    metaTime = Color(0xFF9FB89A),
    wallpaperTop = Color(0xFF0E1A12),
    wallpaperBottom = Color(0xFF16261A),
    wallpaperDoodle = Color(0x09FFFFFF),
    datePillBackground = Color(0x73000000),
    sysName = Color(0xFFFFD98A),

    onMedia = Color(0xFFFFFFFF),
    onMediaMuted = Color(0x59FFFFFF),
    checkReadOnMedia = Color(0xFF66A9FF),

    overlay = Color(0x66000000),
    overlayStrong = Color(0xB8000000),
    shadowSoft = Color(0x2E000000),
    shadowStrong = Color(0x47000000),
    subtleFill = Color(0x12FFFFFF),
    neutralControl = Color(0x4D7F7F7F),
    swipeNeutral = Color(0xFF8E8E93),
    swipePositive = Color(0xFF30D158),
    avatarRing = Color(0x52FFFFFF),
    // 深色己方气泡是深绿底（0xFF1F4D2E），iOS 那枚深绿键压上去几乎看不见——深色下退回强调色
    voicePlayMine = Color(0xFF4CA64C),
    voiceWaveInactive = Color(0x47A0A7B0),
    voiceWaveInactiveMine = Color(0x52E6E8EB),
    voiceSpeedBg = Color(0x244CA64C),
    voiceSpeedBgMine = Color(0x24E6E8EB),

    isDark = true,
)

/**
 * 圆角、间距等尺寸令牌。
 *
 * 圆角与页面级留白见 IMServer `docs/UI_COLOR.md` §4；
 * **组件级尺寸（头像、行高、气泡、输入栏）见 `docs/UI_SPEC.md`，每个值那里都标了三端出处。**
 *
 * 下面带「§」注释的值都是**从 iOS/Web 代码里量出来的**，不是按 Android 惯例拍的——
 * 写死在页面里的魔法数字曾让本端头像 48（iOS/Web 都是 52）、气泡宽 280dp 固定
 * （iOS/Web 都是比例）。要改先改 `UI_SPEC.md`，那是三端共同的基准。
 */
data class IMDimens(
    val radiusCard: Dp = 14.dp,
    val radiusBubble: Dp = 18.dp,
    val radiusMenu: Dp = 8.dp,
    /** 应用内横幅圆角（NOTIFICATIONS_P1_DESIGN §1.2 表格：Android 版 12，与 iOS 的 14 是刻意的正当差异）。 */
    val radiusBanner: Dp = 12.dp,
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    /** 页面左右边距，UI_COLOR §4 规定为 16。 */
    val space4: Dp = 16.dp,
    /** 同级卡片间距 ≥12；外观设置里各卡间距 ≥24。 */
    val cardGap: Dp = 12.dp,
    val sectionGap: Dp = 24.dp,

    // —— 会话列表（UI_SPEC §2）——
    /** 会话行头像直径。iOS `kIMAvatarSize` / Web `.avatar` 同为 52。 */
    val convAvatar: Dp = 52.dp,
    /** 会话行高。iOS `tableView.rowHeight` 写死 76；本端按 12+52+12 自然得到，取 min 兜底。 */
    val convRowHeight: Dp = 76.dp,
    /** 分割线左缩进 = 行左边距 + 头像 + 间距，与头像右缘对齐（iOS `separatorInset` 同口径）。 */
    val convSeparatorInset: Dp = space4 + convAvatar + space3,

    // —— 聊天页（UI_SPEC §3）——
    /**
     * 气泡最大宽占内容区的**比例**。iOS `_bubble.widthAnchor` multiplier 0.75、Web `.row` 72%。
     * **必须是比例不能是固定 dp**：280dp 在 360dp 机器上占 78%、411dp 机器上占 68%，两头都不对。
     */
    val bubbleMaxWidthFraction: Float = 0.75f,
    /** 气泡左右内边距。基准 12（iOS `_text` leading/trailing；2026-09-07 拍板按 iOS）。 */
    val bubblePaddingH: Dp = 12.dp,
    /** 气泡上下内边距。iOS/Web 同为 6。 */
    val bubblePaddingV: Dp = 6.dp,
    /** 群内发送者头像。基准 30（iOS `IMBubbleCell` `_avatar`）。 */
    val chatAvatar: Dp = 30.dp,
    /** 头像距 cell 左 12、头像与气泡间隙 6 —— 合起来就是 iOS 的 `_leading.constant = 48`。 */
    val chatAvatarLeading: Dp = 12.dp,
    val chatAvatarGap: Dp = 6.dp,
    /** 会话行未读徽标高。基准 20（iOS `_badge.heightAnchor`）。 */
    val unreadBadgeHeight: Dp = 20.dp,
    /** 跳到底部悬浮钮。移动端基准 36（iOS `jumpButton`）；Web 维持 40。 */
    val jumpButton: Dp = 36.dp,
    /** 日期分隔胶囊高。iOS `_datePillHeight` 24。 */
    val datePillHeight: Dp = 24.dp,
    /** 消息行间距 5 = 气泡底距 cell 底 3 + 气泡顶距 2（iOS `_bubbleBottom −3` / `_bubbleTopPlain +2`，设计稿 §1）。 */
    val chatRowGap: Dp = 5.dp,
    /** 列表上内边距 = 首行 cell 自带的顶距 2。 */
    val chatListPaddingTop: Dp = 2.dp,
    /** 最后一条气泡距输入栏 3（iOS `contentInset.bottom` 恒 0，这 3 全靠 cell 自带的底距；设计稿 #3）。 */
    val chatListPaddingBottom: Dp = 3.dp,

    // —— 顶部标题栏（UI_SPEC §4.5）——
    /**
     * 标题栏左右两侧的固定占位宽。**必须是固定值**：靠 weight 撑，标题会随左右内容长度左右漂，
     * 翻页时肉眼可见地抖一下（这正是「居中」这个需求的由来）。
     */
    val topBarSide: Dp = 64.dp,
    /**
     * 标题栏**固定高**（不含状态栏）。对齐 iOS `kIMLiquidBarHeight = 56`（上 6 + 按钮 44 + 下 6）。
     * 此前是「内容高 + 上下各 12」，内容高随场景变（返回箭头 24 / 头像 32 / 双行标题约 36），
     * 栏高在 48~60 之间漂，页与页之间肉眼可见地不一致（2026-10-05 用户报）。
     */
    val topBarHeight: Dp = 56.dp,
    /** 右侧会话头像直径。**这是 Android 先行的能力**，iOS 标题栏右侧目前只有图标/文字动作。 */
    val topBarAvatar: Dp = 32.dp,
    /** 左键图标（返回箭头等）。 */
    val topBarIcon: Dp = 24.dp,
    /**
     * 右侧**圆形图标钮**直径（通讯录「添加朋友」、消息页 ＋）。iOS `actionCircular` 是 44pt；
     * 本端标题栏固定高 [topBarHeight]=56，取 36（UI_SPEC §4.5）。栏高是固定值，圆钮在栏内垂直居中即可，
     * 不再靠「只占 [topBarIcon] 的高、上下溢出」去凑一级页与二级页等高。
     */
    val topBarCircleButton: Dp = 36.dp,
    /** 圆形图标钮里的图标。 */
    val topBarCircleIcon: Dp = 20.dp,

    // —— 输入栏（UI_SPEC §4）——
    /** 输入栏**单行态**总高。iOS `inputBar.heightAnchor` 56；多行时本端允许长高。 */
    val inputBarHeight: Dp = 56.dp,
    /** 输入栏左右功能钮。iOS `plusButton`/`sendButton` 同为 36。 */
    val inputControl: Dp = 36.dp,
    /** 输入栏按钮距栏边。移动端基准 8（iOS `plusButton` leading）；Web 维持 16。 */
    val inputBarEdge: Dp = 8.dp,

    // —— 「我」页 / 分组设置表 ——
    // 出处一律是 iOS `IMSettingsViewController` / `IMDeviceListViewController` /
    // `IMDeviceDetailViewController` / `IMProfileEditViewController` / `IMQRCardViewController`
    // 里的常数（本页整体以 iOS 为基准）。**这一组还没进 `docs/UI_SPEC.md`**——
    // 那张表目前只覆盖会话列表/聊天页/输入栏三块，「我」页是欠账；补表前请勿在页面里就地改数字。
    /** 设置行最小高。iOS `heightForRowAtIndexPath` 返回 50。 */
    val settingsRowHeight: Dp = 50.dp,
    /** 彩色图标方块边长与圆角。iOS `_iconBg` 30 / `cornerRadius = 7`。 */
    val settingsIcon: Dp = 30.dp,
    val radiusSettingsIcon: Dp = 7.dp,
    /** 方块里的图标本身。iOS `_iconView` 18。 */
    val settingsIconGlyph: Dp = 18.dp,
    /** 设置行分割线左缩进 = 行左边距 + 图标 + 间距，与标题起点对齐（iOS `separatorInset` 同口径）。 */
    val settingsSeparatorInset: Dp = space4 + settingsIcon + space3,
    /** 设备行的 emoji 图标盒。iOS `iconBox` 36 / `cornerRadius = 9`。 */
    val deviceRowIcon: Dp = 36.dp,
    val radiusDeviceIcon: Dp = 9.dp,
    /** 「我」页头部与资料页只读态的大头像。iOS `roAvatar` 96。 */
    val profileAvatar: Dp = 96.dp,
    /** 资料页**编辑态**头像（比只读态小一圈，给相机角标让位）。iOS `avatarView` 86。 */
    val profileEditAvatar: Dp = 86.dp,
    /** 编辑态头像右下角的相机角标。iOS `cam` 28。 */
    val cameraBadge: Dp = 28.dp,
    /** 名片码卡片里的小头像。iOS `IMQRCardView` 头像 56。 */
    val qrCardAvatar: Dp = 56.dp,
    /** 二维码画布边长。iOS 是按卡片宽自适应，本端取一个够扫的定值。 */
    val qrCode: Dp = 240.dp,
)

/**
 * 「我」页设置行的**图标底色板**。
 *
 * 逐个对齐 iOS `IMSettingsViewController` 用的 `UIColor.system*`（Apple 公布的 sRGB 取值），
 * 因为「我」页整体以 iOS 为基准。**它不在 `UI_COLOR.md` 里**：三端在这一处本就分叉
 * —— Web 的设置行是无底色的 lucide 线图标，iOS 是彩色圆角方块。要统一是产品拍板的事，
 * 不是在这里悄悄选一边。改动请连同 UI_COLOR.md / UI_SPEC.md 一起谈。
 *
 * 之所以仍收进令牌而不是就地写 Hex：散在页面里的颜色改不动也查不着（Tokens 顶部三条硬规则）。
 * 深浅两套取值分别对应 Apple 的 light / dark 变体。
 */
data class IMSettingsIconColors(
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val gray: Color,
    val yellow: Color,
    val purple: Color,
    val teal: Color,
    /** systemPink（隐私与安全「生日」行）。 */
    val pink: Color,
)

/** systemBlue/Green/... 的浅色取值。 */
val LightSettingsIconColors = IMSettingsIconColors(
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    gray = Color(0xFF8E8E93),
    yellow = Color(0xFFFFCC00),
    purple = Color(0xFFAF52DE),
    teal = Color(0xFF30B0C7),
    pink = Color(0xFFFF2D55),
)

/** systemBlue/Green/... 的深色取值（Apple 在深色下把这几个色调亮了一档）。 */
val DarkSettingsIconColors = IMSettingsIconColors(
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    gray = Color(0xFF8E8E93),
    yellow = Color(0xFFFFD60A),
    purple = Color(0xFFBF5AF2),
    teal = Color(0xFF40C8E0),
    pink = Color(0xFFFF375F),
)

/**
 * 用户可调的外观值（对应 iOS `IMAppearance` / Web `--msg-font`、`--radius-bubble`）。
 * 这些**不是常量**，来自外观偏好层；页面不得直接读写本地存储（§7.3）。
 */
data class IMAppearance(
    /** 聊天正文字号，用户可调 14～22（§3）。 */
    val chatFontSize: TextUnit = 15.sp,
    /** 气泡圆角，用户可调 6～24（§2）；输入框圆角也跟它走（UI_SPEC §4）。 */
    val bubbleRadius: Dp = 18.dp,
    /** 聊天区壁纸样式（颜色来自 [IMColors.wallpaperTop]/[IMColors.wallpaperBottom]，随主题变）。 */
    val wallpaper: ChatWallpaper = ChatWallpaper.DOODLE,
    /** 「动画」开关：关掉后界面弹出类动效直接到终态（iOS `IMAnimator` 同口径，触感反馈不受影响）。 */
    val animationsEnabled: Boolean = true,
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
