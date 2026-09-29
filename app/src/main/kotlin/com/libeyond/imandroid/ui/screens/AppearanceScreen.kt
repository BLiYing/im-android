package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AppearancePrefs
import com.libeyond.imandroid.data.ChatThemeId
import com.libeyond.imandroid.data.ChatWallpaper
import com.libeyond.imandroid.ui.AppIconChoice
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMSwitchRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.theme.IMThemeMode

/**
 * 外观（对齐 iOS `IMAppearanceViewController` 主页）。四张卡片**逐行照抄 iOS**：
 *  1. 主题颜色：聊天预览 + 横向主题条 +「聊天主题」「聊天壁纸」两行
 *  2. 显示模式：「夜间模式」开关 +「自动夜间模式」行（进三选一）
 *  3. 聊天外观：「字号」「信息框圆角」两行（进滑块页）+「动画」开关
 *  4. 应用图标：四选一
 * 右上「还原」= 全部回出厂值 + 图标回默认（iOS 同，不二次确认）。
 *
 * 纯展示：值从参数进、改动从回调出，状态在 [com.libeyond.imandroid.ui.AppearanceHost]。
 */
@Composable
fun AppearanceScreen(
    prefs: AppearancePrefs,
    appIcon: AppIconChoice,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onPickTheme: (ChatThemeId) -> Unit,
    onOpenThemes: () -> Unit,
    onOpenWallpapers: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onOpenMode: () -> Unit,
    onOpenFontSize: () -> Unit,
    onOpenBubbleRadius: () -> Unit,
    onAnimationsChange: (Boolean) -> Unit,
    onPickIcon: (AppIconChoice) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = stringResource(R.string.ios_settings_row_appearance),
            onLeft = onBack,
            actionText = stringResource(R.string.appearance_reset),
            // 已是出厂值时灰掉而不是藏掉（IMActionRow 同一条理由：藏了再冒出来，标题栏会跳）
            actionEnabled = !prefs.isDefault || appIcon != AppIconChoice.DEFAULT,
            onAction = onReset,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            IMSectionHeader(stringResource(R.string.appearance_section_theme_color))
            IMSettingsGroup {
                AppearanceChatPreview(
                    theme = prefs.theme,
                    wallpaper = prefs.wallpaper,
                    fontSize = prefs.chatFontSize,
                    bubbleRadius = prefs.bubbleRadius,
                    modifier = Modifier.fillMaxWidth().height(238.dp),
                )
                ThemeStrip(prefs.theme, onPickTheme)
                IMRowDivider(insetStart = d.space4)
                IMSettingsRow(stringResource(R.string.appearance_chat_theme), onOpenThemes, rightValue = themeName(prefs.theme))
                IMRowDivider(insetStart = d.space4)
                IMSettingsRow(stringResource(R.string.general_wallpaper), onOpenWallpapers, rightValue = wallpaperName(prefs.wallpaper))
            }

            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.appearance_mode_title))
            IMSettingsGroup {
                // iOS 口径：开 = 深色；关 = **跟随系统**（不是浅色）
                IMSwitchRow(stringResource(R.string.appearance_row_night_mode), prefs.mode == IMThemeMode.Dark, onNightModeChange)
                IMRowDivider(insetStart = d.space4)
                IMSettingsRow(stringResource(R.string.appearance_row_auto_night_mode), onOpenMode, rightValue = modeName(prefs.mode))
            }

            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.appearance_section_chat_appearance))
            IMSettingsGroup {
                IMSettingsRow(stringResource(R.string.appearance_row_font_size), onOpenFontSize, rightValue = prefs.chatFontSize.toString())
                IMRowDivider(insetStart = d.space4)
                IMSettingsRow(stringResource(R.string.appearance_bubble_radius_title), onOpenBubbleRadius, rightValue = prefs.bubbleRadius.toString())
                IMRowDivider(insetStart = d.space4)
                IMSwitchRow(stringResource(R.string.appearance_row_animation), prefs.animationsEnabled, onAnimationsChange)
            }

            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.appearance_section_app_icon))
            IMSettingsGroup {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIconChoice.entries.forEach { choice ->
                        AppIconButton(choice, choice == appIcon, { onPickIcon(choice) }, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** 横向主题条：选中项进场时滚到可见（iOS 没做这一步，14 个主题选了后面的，回来看不到选中态）。 */
@Composable
private fun ThemeStrip(selected: ChatThemeId, onPick: (ChatThemeId) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { state.scrollToItem(ChatThemeId.entries.indexOf(selected).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ChatThemeId.entries, key = { it.wire }) { t ->
            ThemeMiniCard(t, themeName(t), t == selected, { onPick(t) })
        }
    }
}

@Composable
private fun AppIconButton(choice: AppIconChoice, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .then(if (selected) Modifier.border(3.dp, c.accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(choice.thumb),
            contentDescription = null,
            // 圆角 = 0.225 × 边长（iOS 图标蒙版的近似值）
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(choice.title),
            color = c.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun themeName(t: ChatThemeId): String = stringResource(
    when (t) {
        ChatThemeId.CLASSIC -> R.string.appearance_theme_classic
        ChatThemeId.OCEAN -> R.string.appearance_theme_ocean
        ChatThemeId.VIOLET -> R.string.appearance_theme_violet
        ChatThemeId.MIDNIGHT -> R.string.appearance_theme_midnight
        ChatThemeId.LIME -> R.string.appearance_theme_lime
        ChatThemeId.TITIAN -> R.string.appearance_theme_titian
        ChatThemeId.MARS_GREEN -> R.string.appearance_theme_mars_green
        ChatThemeId.KLEIN_BLUE -> R.string.appearance_theme_klein_blue
        ChatThemeId.BURGUNDY -> R.string.appearance_theme_burgundy
        ChatThemeId.SCHONBRUNN -> R.string.appearance_theme_schonbrunn
        ChatThemeId.TIFFANY -> R.string.appearance_theme_tiffany
        ChatThemeId.CHINA_RED -> R.string.appearance_theme_china_red
        ChatThemeId.HERMES_ORANGE -> R.string.appearance_theme_hermes_orange
        ChatThemeId.PRUSSIAN_BLUE -> R.string.appearance_theme_prussian_blue
    },
)

@Composable
fun wallpaperName(w: ChatWallpaper): String = stringResource(
    when (w) {
        ChatWallpaper.DOODLE -> R.string.appearance_wallpaper_doodle
        ChatWallpaper.GRADIENT -> R.string.appearance_wallpaper_gradient
        ChatWallpaper.PLAIN -> R.string.appearance_wallpaper_plain
    },
)

@Composable
fun modeName(m: IMThemeMode): String = stringResource(
    when (m) {
        IMThemeMode.System -> R.string.common_follow_system
        IMThemeMode.Light -> R.string.general_theme_light
        IMThemeMode.Dark -> R.string.general_theme_dark
    },
)
