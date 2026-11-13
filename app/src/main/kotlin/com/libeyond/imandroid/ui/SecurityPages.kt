package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChangePasswordRules
import com.libeyond.imandroid.data.PasswordPageMode
import com.libeyond.imandroid.data.PasswordProofMode
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.rememberSecondsLeft
import com.libeyond.imandroid.ui.components.sendCodeLabel
import com.libeyond.imandroid.ui.screens.ChangePasswordScreen
import com.libeyond.imandroid.ui.screens.PasswordCodeUi
import com.libeyond.imandroid.ui.screens.PhoneBindScreen

/**
 * 「隐私与安全 ▸ 登录密码」：设置（没密码）/ 修改（旧密码或短信验证码），版式都是 [ChangePasswordScreen]。
 * 状态在 [PasswordController]（隐私页宿主持有）。
 */
@Composable
fun SecurityPasswordPage(ctl: PasswordController, onToast: (String) -> Unit, onExit: () -> Unit) {
    val leave = { ctl.leave(); onExit() }
    BackHandler(onBack = leave)
    val setting = ctl.mode == PasswordPageMode.Set
    val bySms = ctl.proofMode == PasswordProofMode.Sms
    val left = rememberSecondsLeft(ctl.timer.deadline)
    val f = ctl.form
    ChangePasswordScreen(
        oldPassword = f.old,
        newPassword = f.new,
        confirmPassword = f.confirm,
        // 一改动就清红字（iOS `fieldChanged:`）：给一次重来的机会，但不清输入
        onOldChange = { ctl.edit(f.copy(old = it)) },
        onNewChange = { ctl.edit(ctl.form.copy(new = it)) },
        onConfirmChange = { ctl.edit(ctl.form.copy(confirm = it)) },
        error = ctl.error,
        submitting = ctl.submitting,
        onSubmit = { ctl.submit(onDone = { onToast(it); onExit() }, onToast = onToast) },
        onBack = leave,
        title = stringResource(if (setting) R.string.security_password_set_title else R.string.settings_change_password),
        codeUi = if (!bySms) null else PasswordCodeUi(
            code = f.code,
            onCodeChange = { ctl.edit(ctl.form.copy(code = PhoneAuthRules.sanitizeCode(it))) },
            note = ctl.maskedPhone.takeIf { it.isNotEmpty() }?.let { Str.s(R.string.security_code_sent_to, it) }.orEmpty(),
            sendText = sendCodeLabel(left, ctl.timer.sentOnce),
            sendEnabled = left <= 0 && !ctl.timer.sending,
            onSend = ctl::sendCode,
        ),
        // 修改密码（两种证明方式）都会下线其它设备，都要说；设置密码不会
        footer = if (ChangePasswordRules.showLogoutOthersNote(ctl.mode)) ChangePasswordRules.FOOTER else "",
        helper = ChangePasswordRules.helper(ctl.mode, ctl.proofMode),
        submitText = stringResource(if (setting) R.string.common_done else R.string.settings_change_password),
        switchText = when {
            !ctl.canSwitchToSms -> null
            bySms -> stringResource(R.string.security_password_change_by_password)
            else -> stringResource(R.string.security_password_change_by_sms)
        },
        onSwitch = ctl::toggleProofMode,
    )
}

/** 「隐私与安全 ▸ 登录手机号」：绑定 / 更换，「下一步」弹确认身份弹层。状态在 [PhoneBindController]。 */
@Composable
fun SecurityPhonePage(ctl: PhoneBindController, sec: SecurityState, onToast: (String) -> Unit, onExit: () -> Unit) {
    val leave = { ctl.reset(); onExit() }
    BackHandler(onBack = leave)
    val f = ctl.form
    val left = rememberSecondsLeft(f.timer.deadline)
    PhoneBindScreen(
        changing = ctl.changing,
        currentMasked = sec.status?.phoneMasked.orEmpty(),
        countryCode = f.countryCode,
        phone = f.phone, onPhone = ctl::onPhone,
        code = f.code, onCode = ctl::onCode,
        sendText = sendCodeLabel(left, f.timer.sentOnce),
        sendEnabled = PhoneAuthRules.canSendCode(f.phone, left, f.timer.sending),
        onSend = ctl::sendCode,
        error = ctl.error,
        busy = ctl.busy,
        canNext = ctl.canNext,
        onNext = {
            ctl.next { changed ->
                onToast(Str.s(if (changed) R.string.security_phone_toast_changed else R.string.security_phone_toast_bound))
                onExit()
            }
        },
        onBack = leave,
    )
    ProofSheetHost(ctl.proof)
}
