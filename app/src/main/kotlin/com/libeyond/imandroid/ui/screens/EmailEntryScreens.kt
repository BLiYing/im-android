package com.libeyond.imandroid.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.delay

/**
 * ② 填邮箱 + 当前密码（绑定 / 更换同页，标题与 footer 不同）。
 * 邮箱框：Email 键盘、关纠错 / 自动大写；**任何错误都不清输入**。
 */
@Composable
fun EmailFormScreen(
    changing: Boolean,
    email: String,
    password: String,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    error: String,
    busy: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val d = IMTheme.dimens
    val emailFocus = remember { FocusRequester() }
    val pwFocus = remember { FocusRequester() }
    // 从验证码页「更换邮箱」回来邮箱已填好：直接聚焦密码框
    LaunchedEffect(Unit) { runCatching { (if (email.isEmpty()) emailFocus else pwFocus).requestFocus() } }
    val can = EmailRules.canSend(email, password, busy)
    TwoStepPage(stringResource(if (changing) R.string.email_action_change else R.string.email_action_bind), onBack) {
        Spacer(Modifier.height(d.sectionGap))
        IMSettingsGroup {
            EmailInputRow(email, onEmail, !busy, emailFocus, onNext = { pwFocus.requestFocus() })
            IMRowDivider(insetStart = d.space4)
            PasswordRow(
                value = password, onValueChange = onPassword,
                placeholder = stringResource(R.string.email_field_password),
                isError = false, enabled = !busy, focusRequester = pwFocus,
                imeAction = ImeAction.Done, onImeAction = { if (can) onSubmit() },
            )
        }
        TwoStepNote(error, error = true)
        TwoStepNote(stringResource(if (changing) R.string.email_change_footer else R.string.email_bind_footer))
        Spacer(Modifier.height(d.space4 + d.space1))
        IMPrimaryButton(
            text = stringResource(R.string.email_action_send_code), onClick = onSubmit, modifier = Modifier.padding(horizontal = d.space4),
            enabled = can, loading = busy,
        )
        Spacer(Modifier.height(d.sectionGap))
    }
}

/** 设置行样式的邮箱输入：`KeyboardType.Email` + `ImeAction.Next`，`autoCorrect = false`、`capitalization = None`。 */
@Composable
private fun EmailInputRow(value: String, onChange: (String) -> Unit, enabled: Boolean, focusRequester: FocusRequester, onNext: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val style = MaterialTheme.typography.bodyLarge
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = style.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(stringResource(R.string.email_field_email), color = c.textTertiary, style = style)
                    inner()
                }
            },
        )
    }
}

/**
 * ③ 验证码页：6 位框（复用两步验证的 [BigCodeField]，输满自动提交在 Controller）+ 红字 +
 * 「重新发送」（倒计时由 LaunchedEffect 按 [resendAt] 重算，离开页面即取消）+ 「更换邮箱」。
 */
@Composable
fun EmailCodeScreen(
    sentTo: String,
    code: String,
    onCode: (String) -> Unit,
    error: String,
    busy: Boolean,
    resendAt: Long,
    focusTick: Int,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
    onBack: () -> Unit,
) {
    val d = IMTheme.dimens
    val focus = remember { FocusRequester() }
    var left by remember { mutableIntStateOf(EmailRules.secondsLeft(resendAt, SystemClock.elapsedRealtime())) }
    LaunchedEffect(resendAt) {
        while (true) {
            left = EmailRules.secondsLeft(resendAt, SystemClock.elapsedRealtime())
            if (left <= 0) break
            delay(COUNTDOWN_TICK_MS)
        }
    }
    // 进页 / 重发成功 / 码被拒清空：重新聚焦（框在请求途中是禁用的，等忙态结束）
    LaunchedEffect(focusTick, busy) { if (!busy) runCatching { focus.requestFocus() } }
    TwoStepPage(stringResource(R.string.email_code_title), onBack) {
        TwoStepNote(stringResource(R.string.email_code_sent_to, sentTo))
        Spacer(Modifier.height(d.space4))
        BigCodeField(
            value = code, onChange = onCode, isError = error.isNotEmpty(), enabled = !busy,
            focusRequester = focus, onDone = {}, modifier = Modifier.padding(horizontal = d.space4),
        )
        TwoStepNote(error, error = true)
        Spacer(Modifier.height(d.space3))
        ResendLink(EmailRules.resendLabel(left), enabled = left <= 0 && !busy, onClick = onResend)
        TwoStepLink(stringResource(R.string.email_action_change), { if (!busy) onChangeEmail() }, Modifier.padding(horizontal = d.space4))
        Spacer(Modifier.height(d.sectionGap))
    }
}

private const val COUNTDOWN_TICK_MS = 1000L

/** 「重新发送」：bodyMedium accent 居中；倒计时中 textTertiary 且不可点。 */
@Composable
private fun ResendLink(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    Box(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = IMTheme.dimens.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) c.accent else c.textTertiary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
