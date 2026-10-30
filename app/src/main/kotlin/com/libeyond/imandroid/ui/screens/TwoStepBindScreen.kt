package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.QrEncode
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.QrCodeView
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 绑定认证器：二维码（本地绘制，深浅色都白底）+ 折叠的手动密钥（4 位一组、等宽、复制）+ 6 位码。
 * 错码红字、**输入不清空**。密钥只在内存，离开本页由 Host 丢弃。
 */
@Composable
fun TwoStepBindScreen(
    otpauthUri: String,
    secret: String,
    code: String,
    onCode: (String) -> Unit,
    error: String,
    busy: Boolean,
    onCopySecret: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val matrix = remember(otpauthUri) { QrEncode.encode(otpauthUri) }
    var expanded by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val canSubmit = TwoStepRules.isTotpComplete(code) && !busy
    LaunchedEffect(Unit) { focus.requestFocus() }

    TwoStepPage(stringResource(R.string.twostep_bind_title), onBack) {
        TwoStepNote(stringResource(R.string.twostep_bind_hint))
        Box(Modifier.fillMaxWidth().padding(vertical = d.space3), contentAlignment = Alignment.Center) {
            QrCodeView(matrix, 200.dp, Modifier.clip(RoundedCornerShape(12.dp)))
        }
        IMSettingsGroup {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.twostep_bind_manual), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Image(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.textTertiary))
            }
            if (expanded) {
                IMRowDivider(insetStart = d.space4)
                Text(
                    text = TwoStepRules.groupSecret(secret),
                    color = c.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space3),
                )
                IMRowDivider(insetStart = d.space4)
                TwoStepLink(stringResource(R.string.twostep_bind_copy), onCopySecret, minHeight = d.settingsRowHeight)
            }
        }
        Spacer(Modifier.height(d.space4))
        BigCodeField(
            value = code, onChange = onCode, isError = error.isNotEmpty(), enabled = !busy,
            focusRequester = focus, onDone = { if (canSubmit) onSubmit() },
            modifier = Modifier.padding(horizontal = d.space4),
        )
        TwoStepNote(error, error = true)
        Spacer(Modifier.height(d.space4 + d.space1))
        IMPrimaryButton(stringResource(R.string.twostep_bind_submit), onSubmit, Modifier.padding(horizontal = d.space4), enabled = canSubmit, loading = busy)
        Spacer(Modifier.height(d.sectionGap))
    }
}
