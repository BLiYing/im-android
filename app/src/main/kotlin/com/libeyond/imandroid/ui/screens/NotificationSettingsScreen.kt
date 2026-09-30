package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.NotificationSettings
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMSwitchRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 「通知与提示音」主页（NOTIFICATIONS_DESIGN §2.2）。iOS 逐行照抄的分组与顺序：
 * 消息通知（私聊/群聊子页入口）→ 应用内通知 → 角标计数 → 锁屏与后台通知（P2 占位）→ 重置。
 *
 * 纯展示，状态与写入在 `ui/NotificationSettingsHost.kt`（CODING_STYLE §7②）。
 */
@Composable
internal fun NotificationSettingsScreen(
    settings: NotificationSettings,
    /** 本机有没有振动器（`AlertPlayer.hasVibrator`）——没有就整行不画，同 iOS「设备不支持触感时整行不画」。 */
    hasVibrator: Boolean,
    onOpenType: (group: Boolean) -> Unit,
    onToggleInAppSound: (Boolean) -> Unit,
    onToggleInAppVibrate: (Boolean) -> Unit,
    onToggleInAppPreview: (Boolean) -> Unit,
    onToggleBadge: (Boolean) -> Unit,
    /** 「接收离线推送（本设备）」开关当前值——M5 批次 2 已做实，FCM 令牌开关，见 [onToggleReceivePush]。 */
    receivePushEnabled: Boolean,
    onToggleReceivePush: (Boolean) -> Unit,
    onComingSoon: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons

    val privateTitle = stringResource(R.string.notif_row_private)
    val groupTitle = stringResource(R.string.notif_row_group)
    val inAppPreviewTitle = stringResource(R.string.notif_in_app_preview)
    val systemPermissionTitle = stringResource(R.string.notif_system_permission)

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.ios_settings_row_notifications), onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.sectionGap))
            IMSectionHeader(stringResource(R.string.notif_section_message))
            IMSettingsGroup {
                IMSettingsRow(
                    title = privateTitle,
                    onClick = { onOpenType(false) },
                    icon = Lucide.User,
                    iconBackground = ic.blue,
                    rightValue = typeValueLabel(settings.private),
                )
                IMRowDivider()
                IMSettingsRow(
                    title = groupTitle,
                    onClick = { onOpenType(true) },
                    icon = Lucide.Users,
                    iconBackground = ic.green,
                    rightValue = typeValueLabel(settings.group),
                )
            }
            IMSectionFooter(stringResource(R.string.notif_message_footer))

            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.notif_section_in_app))
            IMSettingsGroup {
                IMSwitchRow(
                    title = stringResource(R.string.notif_in_app_sound),
                    checked = settings.inApp.sound,
                    onCheckedChange = onToggleInAppSound,
                )
                if (hasVibrator) {
                    IMRowDivider(insetStart = d.space4)
                    IMSwitchRow(
                        title = stringResource(R.string.notif_in_app_vibrate),
                        checked = settings.inApp.vibrate,
                        onCheckedChange = onToggleInAppVibrate,
                    )
                }
                IMRowDivider(insetStart = d.space4)
                // P1 应用内横幅（NOTIFICATIONS_P1_DESIGN §1.3）：第一期是灰置占位行，
                // 现在接的是真开关，读写已经存在的 inApp.preview（第一期就存了，默认开）。
                IMSwitchRow(
                    title = inAppPreviewTitle,
                    checked = settings.inApp.preview,
                    onCheckedChange = onToggleInAppPreview,
                )
            }
            IMSectionFooter(stringResource(R.string.notif_in_app_preview_footer))

            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.notif_section_badge))
            IMSettingsGroup {
                IMSwitchRow(
                    title = stringResource(R.string.notif_badge_include_muted),
                    checked = settings.badge.includeMuted,
                    onCheckedChange = onToggleBadge,
                )
            }
            IMSectionFooter(stringResource(R.string.notif_badge_footer))

            // 锁屏与后台通知：「接收离线推送」已做实（M5 批次 2，FCM，见 NotificationSettingsHost 调用点）；
            // 「通知权限」仍是占位——那需要 Android 13+ 运行时权限请求 + 拒绝后跳系统设置整套流程，
            // 本轮未接，留给后续（父任务简报明确允许："UI 入口超出范围就跳过，说清楚"）。
            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.notif_section_system))
            IMSettingsGroup {
                IMSwitchRow(
                    title = stringResource(R.string.notif_system_receive_push),
                    checked = receivePushEnabled,
                    onCheckedChange = onToggleReceivePush,
                )
                IMRowDivider(insetStart = d.space4)
                IMSettingsRow(
                    title = systemPermissionTitle,
                    onClick = { onComingSoon(systemPermissionTitle) },
                    rightValue = stringResource(R.string.notif_system_permission_off),
                    muted = true,
                )
            }
            // 文案仍是「推送通知正在开发中」这句旧占位脚注——它来自跨仓共享的 i18n 源
            // （../../IMServer/docs/i18n/strings.json，`gen-i18n.mjs` 生成，本仓不直接改），
            // 待「通知权限」那一半也做实后再一并请该源更新，这里不提前改错半句话。
            IMSectionFooter(stringResource(R.string.notif_system_footer))

            Spacer(Modifier.height(d.cardGap))
            IMSettingsGroup {
                IMSettingsRow(
                    title = stringResource(R.string.notif_reset),
                    onClick = onReset,
                    icon = null,
                    iconBackground = Color.Unspecified,
                    destructive = true,
                )
            }
            IMSectionFooter(stringResource(R.string.notif_reset_footer))

            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** `notif_value_on_sound`「开 · {sound}」/ `notif_value_off`「关」。主页与类型页都要用。 */
@Composable
internal fun typeValueLabel(t: com.libeyond.imandroid.data.NotifTypeSettings): String =
    if (t.enabled) {
        stringResource(R.string.notif_value_on_sound, soundLabel(t.sound))
    } else {
        stringResource(R.string.notif_value_off)
    }
