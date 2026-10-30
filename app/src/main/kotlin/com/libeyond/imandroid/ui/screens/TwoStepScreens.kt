package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.theme.IMTheme

/** 介绍页（关闭态）：盾牌 + 两段说明 + 「开启两步验证」。 */
@Composable
fun TwoStepIntroScreen(onStart: () -> Unit, onBack: () -> Unit) {
    val d = IMTheme.dimens
    TwoStepPage(stringResource(R.string.twostep_title), onBack) {
        TwoStepHero(stringResource(R.string.twostep_title), stringResource(R.string.twostep_intro_body))
        Spacer(Modifier.height(28.dp))
        IMPrimaryButton(stringResource(R.string.twostep_intro_start), onStart, Modifier.padding(horizontal = d.space4))
        Spacer(Modifier.height(d.sectionGap))
    }
}

/** 状态页（开启态）：开启时间 + 恢复码剩余（≤2 橙色 + footer）+ 关闭。 */
@Composable
fun TwoStepStatusScreen(
    sinceLabel: String,
    remaining: Int,
    unsaved: Boolean,
    onRecovery: () -> Unit,
    onDisable: () -> Unit,
    onBack: () -> Unit,
) {
    val d = IMTheme.dimens
    TwoStepPage(stringResource(R.string.twostep_title), onBack) {
        TwoStepHero(stringResource(R.string.twostep_on_title), sinceLabel)
        Spacer(Modifier.height(d.sectionGap))
        IMSettingsGroup {
            RecoveryRow(remaining, onRecovery)
            IMRowDivider(insetStart = d.space4)
            IMSettingsRow(title = stringResource(R.string.twostep_disable_row), onClick = onDisable, destructive = true)
        }
        IMSectionFooter(TwoStepRules.statusFooter(remaining, unsaved))
        Spacer(Modifier.height(d.sectionGap))
    }
}

/** 「恢复码  剩余 N 个 ›」：N 在 ≤2 时橙色（只染数字，其余保持次要色）。 */
@Composable
private fun RecoveryRow(remaining: Int, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val label = TwoStepRules.remainingLabel(remaining)
    val low = TwoStepRules.isLow(remaining)
    val orange = IMTheme.settingsIcons.orange
    val text = remember(label, low, remaining) {
        buildAnnotatedString {
            val n = remaining.toString()
            val at = label.indexOf(n)
            if (!low || at < 0) append(label) else {
                append(label.substring(0, at))
                withStyle(SpanStyle(color = orange)) { append(n) }
                append(label.substring(at + n.length))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.twostep_recovery_row), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(6.dp))
        Image(Lucide.ChevronRight, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.textTertiary))
    }
}

/**
 * 「密码（+码）」验证页，三处共用：开启前验密码（[showCode] = false）、重新生成恢复码、关闭。
 * 码框可切「改用恢复码」。值 / 错误 / 忙态全由 Host 持有（请求挂 `client.scope`，返回再进来不丢结果）。
 */
@Composable
fun TwoStepVerifyScreen(
    title: String,
    footer: String,
    submitText: String,
    password: String,
    onPassword: (String) -> Unit,
    showCode: Boolean,
    code: String,
    onCode: (String) -> Unit,
    recoveryMode: Boolean,
    onToggleRecovery: () -> Unit,
    error: String,
    busy: Boolean,
    canSubmit: Boolean,
    danger: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val d = IMTheme.dimens
    val pwFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { pwFocus.requestFocus() }
    LaunchedEffect(recoveryMode) { if (showCode && code.isEmpty() && password.isNotEmpty()) codeFocus.requestFocus() }
    TwoStepPage(title, onBack) {
        Spacer(Modifier.height(d.sectionGap))
        IMSettingsGroup {
            PasswordRow(
                value = password, onValueChange = onPassword,
                placeholder = stringResource(R.string.twostep_field_password),
                isError = error.isNotEmpty() && !showCode, enabled = !busy,
                focusRequester = pwFocus,
                imeAction = if (showCode) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Done,
                onImeAction = { if (showCode) codeFocus.requestFocus() else if (canSubmit && !busy) onSubmit() },
            )
            if (showCode) {
                IMRowDivider(insetStart = d.space4)
                CodeRow(
                    value = code, onChange = onCode, recovery = recoveryMode,
                    placeholder = stringResource(if (recoveryMode) R.string.twostep_field_recovery else R.string.twostep_field_code),
                    enabled = !busy, focusRequester = codeFocus,
                    onDone = { if (canSubmit && !busy) onSubmit() },
                )
            }
        }
        if (showCode) {
            TwoStepLink(
                stringResource(if (recoveryMode) R.string.twostep_use_code else R.string.twostep_use_recovery),
                onToggleRecovery, Modifier.padding(horizontal = d.space4),
            )
        }
        TwoStepNote(error, error = true)
        TwoStepNote(footer)
        Spacer(Modifier.height(d.space4 + d.space1))
        IMPrimaryButton(
            text = submitText, onClick = onSubmit, modifier = Modifier.padding(horizontal = d.space4),
            enabled = canSubmit && !busy, loading = busy, danger = danger,
        )
        Spacer(Modifier.height(d.sectionGap))
    }
}
