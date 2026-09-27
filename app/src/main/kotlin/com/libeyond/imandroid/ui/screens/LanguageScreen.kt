package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LanguagePref
import com.libeyond.imandroid.data.ResolvedLanguage
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 语言（对齐 iOS `IMLanguageViewController` / Web `LanguagePanel`）：三选一，点即选中即返回，
 * 没有「确定」按钮。文案接 `IMServer/docs/i18n/strings.json` 生成的 `R.string.settings_language_*`
 * （本页与登录页是本端应用内多语言的两个试点模块，其余界面仍待迁移）。
 */
@Composable
fun LanguageScreen(current: LanguagePref, onSelect: (LanguagePref) -> Unit, onBack: () -> Unit) {
    val c = IMTheme.colors

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.settings_language_title), onLeft = onBack)
        Spacer(Modifier.height(IMTheme.dimens.sectionGap))
        IMSettingsGroup {
            LanguagePref.entries.forEachIndexed { i, pref ->
                if (i > 0) IMRowDivider()
                LanguageOptionRow(
                    title = languagePrefDisplayName(pref),
                    selected = pref == current,
                    onClick = { onSelect(pref) },
                )
            }
        }
        IMSectionFooter(stringResource(R.string.settings_language_footer))
    }
}

/** 语言设置页三个选项、以及「我」页语言行右值都用这个（后者见 `MeHost.kt`）。 */
@Composable
fun languagePrefDisplayName(pref: LanguagePref): String = when (pref) {
    LanguagePref.SYSTEM -> stringResource(R.string.settings_language_option_system)
    LanguagePref.ZH_HANS -> stringResource(R.string.settings_language_option_zh_hans)
    LanguagePref.EN -> stringResource(R.string.settings_language_option_en)
}

@Composable
fun resolvedLanguageDisplayName(language: ResolvedLanguage): String = when (language) {
    ResolvedLanguage.ZH_HANS -> stringResource(R.string.settings_language_option_zh_hans)
    ResolvedLanguage.EN -> stringResource(R.string.settings_language_option_en)
}

/** 「我」页语言行的右值：跟随系统时带上解析结果（对齐 iOS `currentPreferenceLabel`），如「跟随系统（简体中文）」。 */
@Composable
fun languageCurrentLabel(pref: LanguagePref, resolved: ResolvedLanguage): String =
    if (pref == LanguagePref.SYSTEM) {
        stringResource(R.string.settings_language_current_system, resolvedLanguageDisplayName(resolved))
    } else {
        languagePrefDisplayName(pref)
    }

@Composable
private fun LanguageOptionRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = d.settingsRowHeight)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Image(Lucide.Check, null, Modifier.height(18.dp), colorFilter = ColorFilter.tint(c.accent))
        }
    }
}
