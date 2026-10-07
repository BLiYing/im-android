package com.libeyond.imandroid.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.Zap
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.PowerSaveDecision
import com.libeyond.imandroid.data.PowerSaveMode
import com.libeyond.imandroid.data.PowerSaveReason
import com.libeyond.imandroid.data.PowerSaveStatus
import com.libeyond.imandroid.data.PowerSavingPrefs
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMSwitchRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlin.math.roundToInt

/** 耗电项（页内四行，顺序即界面顺序）。 */
enum class PowerSavingItem { Animations, AutoDownload, VideoPreload, BackgroundConnection }

/**
 * 「省电模式」页（POWER_SAVING_UX_SKETCH §3 a2 / a3，**逐项照稿，改数字先改稿**）。
 * 页面节奏照 `NotificationSettingsScreen`：顶栏 → Spacer(sectionGap) → 状态行 → header / 组 / footer …
 * 纯展示：状态与写入在 `ui/PowerSavingHost.kt`。
 */
@Composable
fun PowerSavingScreen(
    prefs: PowerSavingPrefs,
    status: PowerSaveStatus,
    animationsPref: Boolean,
    /** 「后台保持连接」前提（服务端 fcm_enabled 且本次登录令牌已上报）不满足时 false。 */
    backgroundAvailable: Boolean,
    onMode: (PowerSaveMode) -> Unit,
    onThreshold: (Int) -> Unit,
    onFollowSystem: (Boolean) -> Unit,
    onItem: (PowerSavingItem, Boolean) -> Unit,
    onLockedClick: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.ios_settings_row_power_saving), onLeft = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.sectionGap))
            IMSettingsGroup {
                IMSettingsRow(
                    title = stringResource(if (status.active) R.string.power_saving_status_on else R.string.power_saving_status_off),
                    onClick = null,
                    icon = Lucide.Zap,
                    iconBackground = ic.yellow,
                    subtitle = statusSubtitle(prefs, status),
                    // 不显示电量百分比（2026-10-07 定，与 iOS 同）：状态栏已有精确电量；
                    // iOS 17+ 系统只给 5% 粒度的读数，摆在这里只会与状态栏对不上
                )
            }

            // 稿：状态组与 header 之间 Spacer(12) + header 上内边距 4 = 16 = IMSectionHeader 自带的上 16
            IMSectionHeader(stringResource(R.string.power_saving_mode_header))
            IMSettingsGroup {
                ModeRow(R.string.power_saving_mode_off, stringResource(R.string.power_saving_mode_off_sub), prefs.mode == PowerSaveMode.OFF) {
                    onMode(PowerSaveMode.OFF)
                }
                IMRowDivider(insetStart = d.space4)
                ModeRow(
                    R.string.power_saving_mode_auto,
                    stringResource(R.string.power_saving_mode_auto_sub, prefs.threshold),
                    prefs.mode == PowerSaveMode.AUTO,
                ) { onMode(PowerSaveMode.AUTO) }
                IMRowDivider(insetStart = d.space4)
                ModeRow(R.string.power_saving_mode_always, stringResource(R.string.power_saving_mode_always_sub), prefs.mode == PowerSaveMode.ALWAYS) {
                    onMode(PowerSaveMode.ALWAYS)
                }
                AnimatedVisibility(visible = prefs.mode == PowerSaveMode.AUTO) {
                    Column {
                        IMRowDivider(insetStart = d.space4)
                        ThresholdRow(prefs.threshold, onThreshold)
                    }
                }
            }
            IMSectionFooter(stringResource(R.string.power_saving_mode_footer))

            Spacer(Modifier.height(d.cardGap))
            IMSettingsGroup {
                IMSwitchRow(
                    title = stringResource(R.string.power_saving_follow_title),
                    checked = prefs.followSystem,
                    onCheckedChange = onFollowSystem,
                    icon = Lucide.Smartphone,
                    iconBackground = ic.blue,
                    subtitle = stringResource(R.string.power_saving_follow_sub),
                )
            }

            IMSectionHeader(stringResource(R.string.power_saving_items_header))
            val paused = stringResource(R.string.power_saving_item_paused)
            IMSettingsGroup {
                ItemRow(PowerSavingItem.Animations, Lucide.Sparkles, ic.orange, R.string.power_saving_item_animations,
                    stringResource(R.string.power_saving_item_animations_sub), animationsPref, status.active, paused, true, onItem, onLockedClick)
                IMRowDivider()
                ItemRow(PowerSavingItem.AutoDownload, Lucide.Download, ic.green, R.string.power_saving_item_auto_download,
                    stringResource(R.string.power_saving_item_auto_download_sub), prefs.autoDownload, status.active, paused, true, onItem, onLockedClick)
                IMRowDivider()
                ItemRow(PowerSavingItem.VideoPreload, Lucide.Video, ic.red, R.string.power_saving_item_video_preload,
                    stringResource(R.string.power_saving_item_video_preload_sub), prefs.videoPreload, status.active, paused, true, onItem, onLockedClick)
                IMRowDivider()
                ItemRow(PowerSavingItem.BackgroundConnection, Lucide.Activity, ic.purple, R.string.power_saving_item_background_connection,
                    stringResource(
                        if (backgroundAvailable) R.string.power_saving_item_background_connection_sub
                        else R.string.power_saving_item_background_connection_unavailable,
                    ),
                    prefs.backgroundConnection, status.active && backgroundAvailable, paused, backgroundAvailable, onItem, onLockedClick)
            }
            IMSectionFooter(stringResource(R.string.power_saving_items_footer))
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

@Composable
private fun statusSubtitle(prefs: PowerSavingPrefs, status: PowerSaveStatus): String {
    if (!status.active) {
        return if (prefs.mode == PowerSaveMode.AUTO) {
            stringResource(R.string.power_saving_status_hint_auto, prefs.threshold)
        } else {
            stringResource(R.string.power_saving_status_hint_off)
        }
    }
    val reason = when (status.reason) {
        PowerSaveReason.ALWAYS -> stringResource(R.string.power_saving_reason_always)
        PowerSaveReason.BATTERY -> stringResource(R.string.power_saving_reason_battery, prefs.threshold)
        else -> stringResource(R.string.power_saving_reason_system)
    }
    return stringResource(R.string.power_saving_status_active, reason, status.pausedCount)
}

/** 开启方式行：min 58、左右 16、无左图标；选中项右侧 Check 18（照 `AppearanceModeScreen.ModeRow`，去图标加副标题）。 */
@Composable
private fun ModeRow(titleRes: Int, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 58.dp)
            .padding(horizontal = IMTheme.dimens.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IMTheme.dimens.space3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleRes), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (selected) Image(Lucide.Check, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.accent))
    }
}

/** 阈值滑块行：内边距 16 / 12 / 14，标题行→滑块 6；5..50、steps = 8（步长 5）；颜色照 `AppearanceSliderScreen`。 */
@Composable
private fun ThresholdRow(threshold: Int, onThreshold: (Int) -> Unit) {
    val c = IMTheme.colors
    var position by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }
    val shown = position.roundToInt().coerceIn(PowerSaveDecision.THRESHOLD_MIN, PowerSaveDecision.THRESHOLD_MAX)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.power_saving_threshold_label), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text("$shown%", color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = position,
            onValueChange = { position = it },
            // 松手才写：拖动中只改显示（拖一路每档一次落盘 + 重算是浪费）
            onValueChangeFinished = { onThreshold(position.roundToInt()) },
            valueRange = PowerSaveDecision.THRESHOLD_MIN.toFloat()..PowerSaveDecision.THRESHOLD_MAX.toFloat(),
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.separator,
                activeTickColor = c.accent,
                inactiveTickColor = c.separator,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${PowerSaveDecision.THRESHOLD_MIN}%", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text("${PowerSaveDecision.THRESHOLD_MAX}%", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ItemRow(
    item: PowerSavingItem,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: androidx.compose.ui.graphics.Color,
    titleRes: Int,
    subtitle: String,
    value: Boolean,
    locked: Boolean,
    pausedSubtitle: String,
    enabled: Boolean,
    onItem: (PowerSavingItem, Boolean) -> Unit,
    onLockedClick: () -> Unit,
) {
    IMSwitchRow(
        title = stringResource(titleRes),
        checked = value,
        onCheckedChange = { onItem(item, it) },
        enabled = enabled,
        icon = icon,
        iconBackground = iconBg,
        subtitle = if (locked) pausedSubtitle else subtitle,
        locked = locked,
        onLockedClick = onLockedClick,
    )
}
