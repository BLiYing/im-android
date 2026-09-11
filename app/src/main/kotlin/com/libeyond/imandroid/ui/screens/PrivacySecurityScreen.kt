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
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.AlignLeft
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.CircleUserRound
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.Trash2
import com.libeyond.imandroid.data.PrivacyAction
import com.libeyond.imandroid.data.PrivacyIcon
import com.libeyond.imandroid.data.PrivacySecurity
import com.libeyond.imandroid.data.PrivacyTint
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMSettingsIconColors
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 「隐私与安全」容器页（对齐 iOS `IMPrivacySecurityViewController`）。
 *
 * 分组、文案、占位右值全在 [PrivacySecurity.groups]，这里只画；纯展示，状态在 `PrivacySecurityHost`。
 * 图标照 Web `PrivacySecurityPanel` 选的 lucide（与 iOS SF Symbol 逐个对应）。
 */
@Composable
fun PrivacySecurityScreen(
    /** 黑名单人数；还没拉到为 null（右值留空）。 */
    blockedCount: Int?,
    onBack: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onComingSoon: (String) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = PrivacySecurity.TITLE, onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PrivacySecurity.groups.forEach { group ->
                // 有组头的，组头自带上边距；没有的（第一组）补一段，与「数据和存储」同口径
                if (group.header.isEmpty()) Spacer(Modifier.height(d.sectionGap))
                IMSectionHeader(group.header)
                IMSettingsGroup {
                    group.rows.forEachIndexed { i, row ->
                        if (i > 0) IMRowDivider()
                        val soon = { onComingSoon(row.title) }
                        IMSettingsRow(
                            title = row.title,
                            onClick = when (row.action) {
                                PrivacyAction.Blocked -> onOpenBlocked
                                PrivacyAction.ChangePassword -> onOpenChangePassword
                                PrivacyAction.ComingSoon -> soon
                            },
                            icon = iconOf(row.icon),
                            iconBackground = tintOf(row.tint, ic),
                            rightValue = if (row.action == PrivacyAction.Blocked) {
                                PrivacySecurity.blockedCountLabel(blockedCount)
                            } else {
                                row.value
                            },
                            muted = row.isPlaceholder,
                        )
                    }
                }
                IMSectionFooter(group.footer)
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

private fun iconOf(icon: PrivacyIcon): ImageVector = when (icon) {
    PrivacyIcon.Blocked -> Lucide.Ban
    PrivacyIcon.Key -> Lucide.Key
    PrivacyIcon.TwoStep -> Lucide.ShieldCheck
    PrivacyIcon.Passkey -> Lucide.KeyRound
    PrivacyIcon.Mail -> Lucide.Mail
    PrivacyIcon.Timer -> Lucide.Timer
    PrivacyIcon.Phone -> Lucide.Phone
    PrivacyIcon.LastSeen -> Lucide.Eye
    PrivacyIcon.Avatar -> Lucide.CircleUserRound
    PrivacyIcon.Bio -> Lucide.AlignLeft
    PrivacyIcon.Birthday -> Lucide.Gift
    PrivacyIcon.Trash -> Lucide.Trash2
    PrivacyIcon.Export -> Lucide.FileUp
}

private fun tintOf(tint: PrivacyTint, ic: IMSettingsIconColors): Color = when (tint) {
    PrivacyTint.Red -> ic.red
    PrivacyTint.Blue -> ic.blue
    PrivacyTint.Gray -> ic.gray
    PrivacyTint.Purple -> ic.purple
    PrivacyTint.Teal -> ic.teal
    PrivacyTint.Orange -> ic.orange
    PrivacyTint.Green -> ic.green
    PrivacyTint.Yellow -> ic.yellow
    PrivacyTint.Pink -> ic.pink
}
