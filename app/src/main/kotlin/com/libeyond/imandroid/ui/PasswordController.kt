package com.libeyond.imandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChangePasswordRules
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.PasswordFeedback
import com.libeyond.imandroid.data.PasswordField
import com.libeyond.imandroid.data.PasswordPageMode
import com.libeyond.imandroid.data.PasswordProofMode
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.PhoneAuthApi
import com.libeyond.imandroid.sdk.api.Proof
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.launch

/** 修改 / 设置密码页的输入（`code` = 短信验证码方式）。 */
data class PasswordForm(val old: String = "", val code: String = "", val new: String = "", val confirm: String = "")

/**
 * 「隐私与安全 ▸ 登录密码」（PHONE_AUTH_DESIGN §6.1 / §6.2）：
 * - 没设密码 → 设置：验证码（发到本人号码）+ 新密码两遍，**不**踢其它设备；
 * - 有密码 → 修改：默认旧密码 + 新密码；已绑手机且短信可用时可切「验证码 + 新密码」。
 * 两条都走 `POST /users/me/password {proof, new_password}`（[IMClient.changePassword]，含续期凭据轮换）。
 *
 * 提交挂 `client.scope`：应答里带轮换出的新续期凭据，请求途中返回不能把协程取消。
 * 密码与验证码只在内存，成功 / 离开（非提交中）即清。**日志里不写它们**。
 */
class PasswordController(private val client: IMClient, private val sec: SecurityState) {
    private val log = IMLog.tag("IM.Password")

    var form by mutableStateOf(PasswordForm()); private set
    var error by mutableStateOf<PasswordFeedback.Inline?>(null); private set
    var submitting by mutableStateOf(false); private set
    /** 修改页上点了「忘记旧密码？用短信验证码修改」。 */
    var switchedToSms by mutableStateOf(false); private set
    /** 发码后服务端回的打码号码（比 security 里的新）。 */
    var sentTo by mutableStateOf(""); private set

    /** 本人号码的发码倒计时（与确认身份弹层共用一份）。 */
    val timer: ResendTimer get() = sec.verifyTimer
    val mode: PasswordPageMode get() = PhoneAuthRules.passwordPageMode(sec.status)
    val proofMode: PasswordProofMode get() = PhoneAuthRules.passwordProofMode(mode, switchedToSms, sec.status)
    val canSwitchToSms: Boolean get() = mode == PasswordPageMode.Change && PhoneAuthRules.canChangePasswordBySms(sec.status)
    val maskedPhone: String get() = sentTo.ifEmpty { sec.status?.phoneMasked.orEmpty() }

    fun edit(f: PasswordForm) { form = f; error = null }

    fun toggleProofMode() { switchedToSms = !switchedToSms; error = null; form = form.copy(old = "", code = "") }

    /** 离开页面：提交中则保留（结果回来还要用），否则清掉输入。 */
    fun leave() { if (!submitting) { form = PasswordForm(); error = null; switchedToSms = false } }

    fun sendCode() {
        error = null
        timer.send(client.scope, { client.security.verifyCode(EmailRules.lang()).also { sentTo = it.phoneMasked } }) { e ->
            if (e == null) log.i("verify_code_sent")
            else {
                log.w("verify_code_failed", "code" to ((e as? ApiException)?.code ?: 0))
                error = PasswordFeedback.Inline(sendFailureText(e), PasswordField.Code)
            }
        }
    }

    /** 提交；成功回调 [onDone]（吐司文案），由宿主回容器页。 */
    fun submit(onDone: (toast: String) -> Unit, onToast: (String) -> Unit) {
        if (submitting) return
        val f = form
        val bySms = proofMode == PasswordProofMode.Sms
        val local = if (bySms) ChangePasswordRules.validateWithCode(f.code, f.new, f.confirm) else ChangePasswordRules.validate(f.old, f.new, f.confirm)
        local?.let { error = it; return }
        val setting = mode == PasswordPageMode.Set
        val proof: Proof = if (bySms) Proof.SmsCode(f.code) else Proof.Password(f.old)
        error = null; submitting = true
        client.scope.launch {
            runCatchingCancellable { client.changePassword(proof, f.new) }
                .onSuccess {
                    log.i(if (setting) "password_set" else "password_changed_ok", "bySms" to bySms)
                    sec.update { it.copy(passwordSet = true) }
                    form = PasswordForm(); switchedToSms = false
                    onDone(if (setting) Str.s(R.string.security_password_toast_set) else ChangePasswordRules.SUCCESS_TOAST)
                }
                .onFailure { e ->
                    val api = e as? ApiException
                    val fb = api?.let { ChangePasswordRules.feedbackFor(it.code, PhoneAuthApi.intField(it, "attempts_left")) }
                        ?: PasswordFeedback.Toast(e.userMessage(ChangePasswordRules.FALLBACK))
                    when (fb) {
                        is PasswordFeedback.Inline -> error = fb
                        is PasswordFeedback.Toast -> onToast(fb.message)
                    }
                    if (api?.code == ErrCode.SMS_CODE_EXPIRED) form = form.copy(code = "")
                }
            submitting = false
        }
    }
}
