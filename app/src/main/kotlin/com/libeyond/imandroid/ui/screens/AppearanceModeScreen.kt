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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SunMoon
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.theme.IMThemeMode

/**
 * 显示模式三选一（对齐 iOS `IMAppearanceModeViewController`）：跟随系统 / 浅色 / 深色，
 * 每行前一个强调色图标，当前项打勾，点即生效（不返回，iOS 同——用户常要来回比较）。
 */
@Composable
fun AppearanceModeScreen(current: IMThemeMode, onSelect: (IMThemeMode) -> Unit, onBack: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.appearance_mode_title), onLeft = onBack)
        Spacer(Modifier.height(IMTheme.dimens.sectionGap))
        IMSettingsGroup {
            IMThemeMode.entries.forEachIndexed { i, m ->
                if (i > 0) IMRowDivider()
                ModeRow(modeIcon(m), modeName(m), m == current) { onSelect(m) }
            }
        }
    }
}

private fun modeIcon(m: IMThemeMode): ImageVector = when (m) {
    IMThemeMode.System -> Lucide.SunMoon
    IMThemeMode.Light -> Lucide.Sun
    IMThemeMode.Dark -> Lucide.Moon
}

@Composable
private fun ModeRow(icon: ImageVector, title: String, selected: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 58.dp).padding(horizontal = d.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(icon, null, Modifier.size(d.settingsIcon).padding(3.dp), colorFilter = ColorFilter.tint(c.accent))
        Spacer(Modifier.width(d.space3))
        Text(title, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Image(Lucide.Check, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.accent))
    }
}
