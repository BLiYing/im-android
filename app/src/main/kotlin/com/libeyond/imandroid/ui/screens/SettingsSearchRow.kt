package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Contrast
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.IdCard
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.Zap
import com.libeyond.imandroid.data.SettingsGlyph
import com.libeyond.imandroid.data.SettingsSearchEntry
import com.libeyond.imandroid.data.SettingsTint
import com.libeyond.imandroid.ui.components.SettingsIconTile
import com.libeyond.imandroid.ui.theme.IMSettingsIconColors
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 全局搜索里「设置」分组的一行：左侧与「我」页同款彩色图标方块，标题 + 副标题（一级「我」/ 深层整条路径），
 * 命中词都高亮（口径同其它分组，见 [highlighted]）。纯展示。
 */
@Composable
fun SettingsResultRow(entry: SettingsSearchEntry, keyword: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        Modifier.fillMaxWidth().background(c.surface).clickable(onClick = onClick)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconTile(glyphOf(entry.glyph), tintOf(entry.tint, IMTheme.settingsIcons))
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = highlighted(entry.title, keyword), color = c.textPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = highlighted(entry.subtitle, keyword), color = c.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun glyphOf(g: SettingsGlyph): ImageVector = when (g) {
    SettingsGlyph.Bookmark -> Lucide.Bookmark
    SettingsGlyph.Phone -> Lucide.Phone
    SettingsGlyph.Laptop -> Lucide.Laptop
    SettingsGlyph.IdCard -> Lucide.IdCard
    SettingsGlyph.Bell -> Lucide.Bell
    SettingsGlyph.Lock -> Lucide.Lock
    SettingsGlyph.HardDrive -> Lucide.HardDrive
    SettingsGlyph.Contrast -> Lucide.Contrast
    SettingsGlyph.Zap -> Lucide.Zap
    SettingsGlyph.Globe -> Lucide.Globe
    SettingsGlyph.Ban -> Lucide.Ban
    SettingsGlyph.Key -> Lucide.Key
    SettingsGlyph.Volume -> Lucide.Volume2
    SettingsGlyph.Smartphone -> Lucide.Smartphone
    SettingsGlyph.Wifi -> Lucide.Wifi
    SettingsGlyph.Image -> Lucide.Image
    SettingsGlyph.Video -> Lucide.Video
    SettingsGlyph.File -> Lucide.File
}

private fun tintOf(t: SettingsTint, ic: IMSettingsIconColors): Color = when (t) {
    SettingsTint.Blue -> ic.blue
    SettingsTint.Green -> ic.green
    SettingsTint.Orange -> ic.orange
    SettingsTint.Red -> ic.red
    SettingsTint.Gray -> ic.gray
    SettingsTint.Yellow -> ic.yellow
    SettingsTint.Purple -> ic.purple
    SettingsTint.Teal -> ic.teal
}
