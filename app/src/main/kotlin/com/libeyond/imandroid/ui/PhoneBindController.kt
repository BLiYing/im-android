package com.libeyond.imandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.PhoneAuthApi
import com.libeyond.imandroid.sdk.api.SmsScene
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.launch

/**
 * 「隐私与安全 ▸ 登录手机号」绑定 / 更换（PHONE_AUTH_DESIGN §6.3 / §6.4）：
 * 新号码 → `send-code {scene:"bind"}` → 验证码 →「下一步」弹确认身份弹层 → `POST /users/me/phone`。
 *
 * 两个验证码都是服务端先校验、全部通过才用掉：错在 proof 上留在弹层里重输，错在新号码验证码上收起弹层、
 * 红字显在页面上——**都不清**已填的号码与验证码。**日志里不写号码与验证码**。
 */
class PhoneBindController(private val client: IMClient, private val sec: SecurityState) {
    private val log = IMLog.tag("IM.Phone")

    /** 新号码 + 发到新号码的验证码；倒计时按号码计。 */
    val form = PhoneCodeForm()
    var error by mutableStateOf(""); private set
    /** 红字指向验证码那一格（否则指向号码 / 整页）。 */
    var codeError by mutableStateOf(false); private set
    val proof = ProofController.of(client, sec)

    val changing: Boolean get() = sec.status?.phoneBound == true
    val busy: Boolean get() = proof.busy
    val canNext: Boolean get() = !busy && form.normalizedPhone.isNotEmpty() && PhoneAuthRules.isCodeComplete(form.code)

    fun onPhone(v: String) { form.onPhone(v); error = "" }
    fun onCode(v: String) { form.onCode(v); error = "" }

    /**
     * 进页：清表单（倒计时按号码计，号码清空即清；重新输入同一个号码再发，服务端的间隔会回 `retry_after_sec` 重建倒计时），
     * 按需取一次开放地区的国家码
     * （只有这一页用，别的页面不必为它多打一次 public-config）。
     */
    fun open() {
        reset()
        client.scope.launch { client.publicConfig.fetch()?.let { form.onCountryCode(PhoneAuthRules.defaultCountryCode(it)) } }
    }

    /** 离开：清表单与确认身份弹层（含保留的验证码）。 */
    fun reset() { form.onPhone(""); form.clearCode(); error = ""; codeError = false; proof.clear() }

    fun sendCode() {
        error = ""
        form.send(client.scope, { cc, p -> client.phoneAuth.sendCode(SmsScene.Bind, cc, p, EmailRules.lang()) }) { e ->
            if (e == null) log.i("bind_code_sent") else fail(e, "bind_code_failed", codeField = false, text = sendFailureText(e))
        }
    }

    /** 「下一步」：弹确认身份弹层；证明通过才提交。 */
    fun next(onDone: (changed: Boolean) -> Unit) {
        if (!canNext) return
        val (p, c, cc) = Triple(form.normalizedPhone, form.code, form.countryCode)
        val wasBound = changing
        error = ""
        proof.submit(
            call = { pr -> client.security.bindPhone(pr, cc, p, c) },
            onOk = { masked ->
                log.i("phone_bound", "changed" to wasBound)
                sec.update { it.copy(phoneBound = true, phoneMasked = masked.ifEmpty { it.phoneMasked }) }
                form.onPhone(""); form.clearCode()
                onDone(wasBound)
            },
            onPageError = { e ->
                (e as? ApiException)?.let(form.timer::afterFailure) // 新号码的码过期 / 限流带 retry_after_sec：倒计时跟着重建
                val codeField = (e as? ApiException)?.let { PhoneAuthApi.errorField(it) } == "code"
                fail(e, "phone_bind_failed", codeField, submitFailureText(e))
            },
        )
    }

    private fun fail(e: Throwable, event: String, codeField: Boolean, text: String) {
        log.w(event, "code" to ((e as? ApiException)?.code ?: 0))
        codeError = codeField
        error = text
    }
}
