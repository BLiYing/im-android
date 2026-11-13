package com.libeyond.imandroid.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LoginMode
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.sdk.api.SmsRegion
import com.libeyond.imandroid.ui.LoginPhoneController
import com.libeyond.imandroid.ui.PhoneCodeForm
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.components.PhoneNumberField
import com.libeyond.imandroid.ui.components.SmsCodeField
import com.libeyond.imandroid.ui.components.rememberSecondsLeft
import com.libeyond.imandroid.ui.components.sendCodeLabel
import com.libeyond.imandroid.ui.theme.IMTheme

/** 登录页表单列的最大宽度（稿：表单最大宽 360）。 */
internal val LOGIN_FORM_MAX = 360.dp

/**
 * 登录页页签（稿 2-1）：沿用手写 Row——等分、间距 8、圆角 14、上下 10、15sp；
 * 选中 accentSoft 底 + accent SemiBold，未选 cardBackground 底 + textSecondary。只有一个页签时不画（调用方判）。
 */
@Composable
internal fun LoginTabBar(tabs: List<LoginMode>, selected: LoginMode, onSelect: (LoginMode) -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(Modifier.widthIn(max = LOGIN_FORM_MAX).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.space2)) {
        tabs.forEach { t ->
            val on = t == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(d.radiusCard))
                    .background(if (on) c.accentSoft else c.cardBackground)
                    .clickable { onSelect(t) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (t == LoginMode.Sms) R.string.login_tab_sms else R.string.login_tab_password),
                    color = if (on) c.accent else c.textSecondary,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 「国家码 + 手机号 / 验证码 + 行内发码」两行（验证码登录与忘记密码共用，状态在 [PhoneCodeForm]）。
 * 只有一个开放地区时国家码固定不可点。
 */
@Composable
private fun PhoneCodeFields(
    form: PhoneCodeForm,
    regions: List<SmsRegion>,
    onCountryCode: (String) -> Unit,
    onPhone: (String) -> Unit,
    onCode: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    val left = rememberSecondsLeft(form.timer.deadline)
    PhoneNumberField(
        countryCode = form.countryCode, regions = regions, onCountryCode = onCountryCode,
        phone = form.phone, onPhone = onPhone, label = stringResource(R.string.login_phone_placeholder), enabled = enabled,
    )
    SmsCodeField(
        code = form.code, onCode = onCode,
        sendText = sendCodeLabel(left, form.timer.sentOnce),
        sendEnabled = PhoneAuthRules.canSendCode(form.phone, left, form.timer.sending) && enabled,
        onSend = onSend, enabled = enabled,
    )
}

/** 成功提示（「密码已重设…」「账号已恢复…」）：13sp accent（稿 .lok），不用错误红；有错误时让位。 */
@Composable
internal fun LoginNotice(error: String, notice: String) {
    if (error.isEmpty() && notice.isNotEmpty()) Text(notice, color = IMTheme.colors.accent, style = MaterialTheme.typography.bodyMedium)
}

/** 验证码登录（稿 2-1）：国家码 + 手机号 / 验证码 + 行内发码 / 「登录 / 注册」/ 自动注册说明。 */
@Composable
internal fun LoginSmsForm(ctl: LoginPhoneController, host: String, onSubmit: () -> Unit, notice: String) {
    val c = IMTheme.colors
    val cfg = ctl.config
    PhoneCodeFields(
        ctl.login, cfg?.smsRegions.orEmpty(), ctl::onLoginCountry, ctl::onLoginPhone, ctl::onLoginCode,
        onSend = { ctl.sendLoginCode(host) }, enabled = !ctl.busy,
    )
    val err = ctl.error.ifEmpty { notice }
    IMErrorText(err)
    LoginNotice(err, ctl.notice)
    IMPrimaryButton(
        text = stringResource(R.string.login_sms_submit), onClick = onSubmit,
        enabled = ctl.login.normalizedPhone.isNotEmpty() && ctl.login.code.isNotEmpty(), loading = ctl.busy,
    )
    if (PhoneAuthRules.showAutoRegisterHint(cfg)) {
        Text(
            stringResource(R.string.login_sms_auto_register_hint), color = c.textSecondary,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 「忘记密码？」（左）与「用户名注册」（右）：13sp accent、两端对齐、上下 4（稿 2-1）。都不显示时不占位。 */
@Composable
internal fun LoginLinksRow(showForgot: Boolean, onForgot: () -> Unit, showRegister: Boolean, onRegister: () -> Unit) {
    if (!showForgot && !showRegister) return
    val c = IMTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (showForgot) {
            Text(stringResource(R.string.login_forgot_password), color = c.accent, style = style, modifier = Modifier.clickable(onClick = onForgot).padding(vertical = 4.dp))
        } else Box(Modifier)
        if (showRegister) {
            Text(stringResource(R.string.login_register_username), color = c.accent, style = style, modifier = Modifier.clickable(onClick = onRegister).padding(vertical = 4.dp))
        }
    }
}

/** 同页切换的子页骨架：标题（headlineSmall 居中）+ 可选副标题 + 内容 + 底部「返回」链接（TwoStepLink secondary）。 */
@Composable
private fun LoginSubPane(
    title: String,
    subtitle: String,
    backText: String,
    busy: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    BackHandler(enabled = !busy, onBack = onBack)
    Column(Modifier.widthIn(max = LOGIN_FORM_MAX).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.space3)) {
        Text(title, color = c.textPrimary, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        content()
        TwoStepLink(backText, { if (!busy) onBack() }, secondary = true)
    }
}

/** 完善资料（稿 2-3）：「+86 136****0001」提示 + 昵称 +「完成」+「返回」。本期不加邀请码框。 */
@Composable
internal fun LoginCompletePane(ctl: LoginPhoneController, onSubmit: () -> Unit) {
    LoginSubPane(
        title = stringResource(R.string.register_complete_title),
        subtitle = ctl.completeVerifiedText + "\n" + stringResource(R.string.register_complete_hint),
        backText = stringResource(R.string.login_back_to_login), busy = ctl.busy, onBack = ctl::leaveComplete,
    ) {
        IMTextField(ctl.nickname, ctl::onNickname, stringResource(R.string.login_nickname), enabled = !ctl.busy)
        IMErrorText(ctl.error)
        IMPrimaryButton(
            text = stringResource(R.string.register_complete_submit), onClick = onSubmit,
            enabled = ctl.nickname.isNotBlank(), loading = ctl.busy,
        )
    }
}

/** 忘记密码（稿 2-4）：国家码 + 手机号 / 验证码（`reset` 场景）/ 新密码两遍 /「重设密码」/「返回登录」。 */
@Composable
internal fun LoginResetPane(ctl: LoginPhoneController, host: String) {
    LoginSubPane(
        title = stringResource(R.string.reset_title), subtitle = "",
        backText = stringResource(R.string.login_back_to_login), busy = ctl.busy,
        onBack = { ctl.open(com.libeyond.imandroid.ui.LoginPane.Main) },
    ) {
        PhoneCodeFields(
            ctl.reset, ctl.config?.smsRegions.orEmpty(), ctl::onResetCountry, ctl::onResetPhone, ctl::onResetCode,
            onSend = { ctl.sendResetCode(host) }, enabled = !ctl.busy,
        )
        IMTextField(ctl.newPassword, ctl::onNewPassword, stringResource(R.string.reset_new_password), isPassword = true, enabled = !ctl.busy)
        IMTextField(ctl.confirmPassword, ctl::onConfirmPassword, stringResource(R.string.reset_confirm_password), isPassword = true, enabled = !ctl.busy)
        Text(stringResource(R.string.reset_footer), color = IMTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        IMErrorText(ctl.error)
        IMPrimaryButton(
            text = stringResource(R.string.reset_title), onClick = { ctl.submitReset(host) },
            enabled = ctl.reset.normalizedPhone.isNotEmpty() && ctl.reset.code.isNotEmpty() &&
                ctl.newPassword.isNotEmpty() && ctl.confirmPassword.isNotEmpty(),
            loading = ctl.busy,
        )
    }
}

/**
 * 用户名注册（原「注册」页签的表单，入口改为登录页底部链接「用户名注册」）：用户名 + 密码 + 昵称 + 格式提示 +
 * 暂停注册提示条 + 「注册」+「返回登录」。请求在 `AppRoot`（沿用既有注册路径）。
 */
@Composable
internal fun LoginRegisterPane(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    nickname: String,
    onNickname: (String) -> Unit,
    registerClosed: Boolean,
    error: String,
    busy: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    LoginSubPane(stringResource(R.string.login_register_username), "", stringResource(R.string.login_back_to_login), busy, onBack) {
        IMTextField(username, onUsername, stringResource(R.string.settings_info_username), enabled = !busy)
        IMTextField(password, onPassword, stringResource(R.string.login_password), isPassword = true, enabled = !busy)
        IMTextField(nickname, onNickname, stringResource(R.string.login_nickname), enabled = !busy)
        Text(stringResource(R.string.login_register_format_hint), style = MaterialTheme.typography.bodyMedium, color = c.textTertiary)
        if (registerClosed) {
            // 管理员暂停注册（S4）：提前告知并禁用提交；服务端 /register 照样回 200007 兜底
            Text(
                stringResource(R.string.err_200007), style = MaterialTheme.typography.bodyMedium, color = c.danger,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusCard)).background(c.dangerSoft).padding(d.space3),
            )
        }
        IMErrorText(error)
        IMPrimaryButton(
            text = stringResource(R.string.login_button_register), onClick = onSubmit,
            enabled = !registerClosed && username.isNotBlank() && password.isNotBlank() && nickname.isNotBlank(),
            loading = busy,
        )
    }
}
