package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.LifeBuoy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 关于我们（对齐 iOS `IMAboutViewController` / Web `AboutPanel`）：隐私政策 / 服务条款 / 技术支持 / 版本。
 * 版本行**无 chevron、有右值、整行可点（复制）**——`IMSettingsRow` 的 `onClick != null` 会画 chevron，
 * 所以这里把点击挂在外层 Box、行本身传 null。
 */
@Composable
fun AboutScreen(
    version: String,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit,
    onSupport: () -> Unit,
    onCopyVersion: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.about_title), onLeft = onBack)
        Spacer(Modifier.height(d.sectionGap))
        IMSettingsGroup {
            IMSettingsRow(stringResource(R.string.about_privacy_policy), onPrivacy, icon = Lucide.ShieldCheck, iconBackground = ic.blue)
            IMRowDivider(insetStart = d.settingsSeparatorInset)
            IMSettingsRow(stringResource(R.string.about_terms), onTerms, icon = Lucide.FileText, iconBackground = ic.gray)
            IMRowDivider(insetStart = d.settingsSeparatorInset)
            IMSettingsRow(stringResource(R.string.about_support), onSupport, icon = Lucide.LifeBuoy, iconBackground = ic.green)
            IMRowDivider(insetStart = d.settingsSeparatorInset)
            Box(Modifier.clickable(onClick = onCopyVersion)) {
                IMSettingsRow(
                    stringResource(R.string.about_version), onClick = null,
                    icon = Lucide.Info, iconBackground = ic.gray, rightValue = version,
                )
            }
        }
    }
}
