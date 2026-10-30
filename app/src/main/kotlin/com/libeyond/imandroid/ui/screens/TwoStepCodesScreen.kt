package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 保存恢复码：10 个两列等宽 + 复制全部 + 保存为文件 + 勾选「我已保存」才能点「完成」。
 * 返回 / 顶栏左键都走 [onBack]，由 Host 弹「恢复码只显示这一次」的二次确认。
 */
@Composable
fun TwoStepCodesScreen(
    codes: List<String>,
    regenerated: Boolean,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit,
    onCopyAll: () -> Unit,
    onSaveFile: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    TwoStepPage(stringResource(R.string.twostep_codes_title), onBack) {
        TwoStepNote(stringResource(R.string.twostep_codes_footer))
        if (regenerated) TwoStepNote(stringResource(R.string.twostep_regen_footer))
        Spacer(Modifier.height(d.space3))
        IMSettingsGroup {
            Column(Modifier.fillMaxWidth().padding(d.space4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TwoStepRules.codeRows(codes).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { code ->
                            Text(code, color = c.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(d.space4))
        IMSettingsGroup {
            TwoStepLink(stringResource(R.string.twostep_codes_copy_all), onCopyAll, minHeight = d.settingsRowHeight)
            IMRowDivider(insetStart = d.space4)
            TwoStepLink(stringResource(R.string.twostep_codes_save_file), onSaveFile, minHeight = d.settingsRowHeight)
            IMRowDivider(insetStart = d.space4)
            Row(
                Modifier.fillMaxWidth().clickable { onSavedChange(!saved) }.defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = saved, onCheckedChange = onSavedChange,
                    colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.textTertiary, checkmarkColor = c.onAccent),
                )
                Text(stringResource(R.string.twostep_codes_saved_check), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(d.space4 + d.space1))
        IMPrimaryButton(stringResource(R.string.twostep_codes_done), onDone, Modifier.padding(horizontal = d.space4), enabled = saved)
        Spacer(Modifier.height(d.sectionGap))
    }
}
