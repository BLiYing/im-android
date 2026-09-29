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
import com.libeyond.imandroid.data.NotifSound
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 提示音选择页（NOTIFICATIONS_DESIGN §2.4）。点一下即选中并试听一次，没有「完成」按钮——
 * 与 [LanguageScreen] 同一套「选即生效」交互，连行的画法都照抄（选中态右侧 √）。
 *
 * 试听 / 停止试听由 `ui/NotificationSettingsHost.kt` 接住（`AlertPlayer.preview`/`stopPreview`），
 * 这里只负责画列表 + 转发选中事件，纯展示。
 */
@Composable
internal fun NotificationSoundScreen(current: NotifSound, onSelect: (NotifSound) -> Unit, onBack: () -> Unit) {
    val c = IMTheme.colors

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.notif_sound_title), onLeft = onBack)
        Spacer(Modifier.height(IMTheme.dimens.sectionGap))
        IMSettingsGroup {
            NotifSound.entries.forEachIndexed { i, sound ->
                if (i > 0) IMRowDivider()
                SoundOptionRow(title = soundLabel(sound), selected = sound == current, onClick = { onSelect(sound) })
            }
        }
        IMSectionFooter(stringResource(R.string.notif_sound_footer))
    }
}

/** 提示音 id → 展示名（NotificationSettingsScreen 主页右值、这里的选项行都用它）。 */
@Composable
internal fun soundLabel(sound: NotifSound): String = when (sound) {
    NotifSound.NONE -> stringResource(R.string.notif_sound_none)
    NotifSound.DEFAULT -> stringResource(R.string.notif_sound_default)
    NotifSound.CHORD -> stringResource(R.string.notif_sound_chord)
    NotifSound.CHIME -> stringResource(R.string.notif_sound_chime)
    NotifSound.RISE -> stringResource(R.string.notif_sound_rise)
    NotifSound.DROP -> stringResource(R.string.notif_sound_drop)
}

@Composable
private fun SoundOptionRow(title: String, selected: Boolean, onClick: () -> Unit) {
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
