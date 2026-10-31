package com.libeyond.imandroid.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.EmailErrorKind
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.EmailStep
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.EmailApi
import com.libeyond.imandroid.sdk.api.EmailStatus
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「隐私与安全 ▸ 邮箱登录」的状态与动作（Screen 纯展示，CODING_STYLE §7②）。
 * 由 `PrivacySecurityHost` 持有，请求挂 `client.scope`。
 *
 * 密码只留内存：Form → Code 之后**仍保留**（「重新发送」要再带一次当前密码），
 * 回到 Form（更换邮箱）/ 成功 / 离开页面即清。**日志里不写密码、验证码，邮箱脱敏**。
 */
class EmailController(private val client: IMClient) {
    private val log = IMLog.tag("IM.Email")

    var step by mutableStateOf(EmailStep.Intro); private set
    /** 已取到的状态；null = 还没取到（或一直失败）。失败时保留上一次的值。 */
    var status by mutableStateOf<EmailStatus?>(null); private set

    var email by mutableStateOf(""); private set
    var password by mutableStateOf(""); private set
    var code by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var toast by mutableStateOf<String?>(null)
    var confirmUnbind by mutableStateOf(false)
    /** 验证码页实际发往的邮箱（Form 里的输入可能还在改）。 */
    var sentTo by mutableStateOf(""); private set
    /** 重新发送的可用时刻（[SystemClock.elapsedRealtime] 基准）；倒计时由页面的 LaunchedEffect 据此重算。 */
    var resendAt by mutableLongStateOf(0L); private set
    /** 码被清空时 +1，验证码页据此重新聚焦。 */
    var focusTick by mutableIntStateOf(0); private set

    /** 每次离开 / 返回 +1：在途请求回来发现 epoch 变了就丢弃结果（不把人拽回已离开的页）。 */
    private var epoch = 0
    /** 本地改写 [status] 时 +1，让更早发出的 [refresh] 回包不覆盖更新的结果。 */
    private var statusRev = 0

    val isBound: Boolean get() = status?.bound == true

    suspend fun refresh() {
        val seen = statusRev
        runCatchingCancellable { client.email.status() }
            .onSuccess {
                // 取数期间本地已改过状态（刚绑定 / 解绑成功）：这份回包是旧的，丢掉
                if (seen != statusRev) return@onSuccess
                status = it
                if (!busy && (step == EmailStep.Intro || step == EmailStep.Bound)) step = EmailRules.homeStep(it)
            }
    }

    /** 进页：丢掉上次的表单，按已知状态落页（随后 [refresh] 纠正）。 */
    fun open() = reset()

    /** 离开 / 进入页面：清密码 / 码 / 错误，作废在途请求的回写，落回说明页或已绑定页。 */
    fun reset() {
        epoch++; busy = false; email = ""; password = ""; code = ""; error = ""; sentTo = ""; confirmUnbind = false
        step = EmailRules.homeStep(status)
    }

    fun onEmail(v: String) { email = v; error = "" }
    fun onPassword(v: String) { password = v; error = "" }

    /** 验证码输入；输满 6 位自动提交（复用两步验证的清洗与判定）。 */
    fun onCode(v: String) {
        code = TwoStepRules.sanitizeCode(v); error = ""
        if (step == EmailStep.Code && TwoStepRules.shouldAutoSubmit(code, busy)) submitCode()
    }

    fun startForm() { epoch++; busy = false; email = ""; password = ""; code = ""; error = ""; step = EmailStep.Form }
    fun startUnbind() { epoch++; busy = false; password = ""; error = ""; step = EmailStep.Unbind }

    /** 返回键：true = 已在内部处理；false = 应退出邮箱登录回隐私页。 */
    fun back(): Boolean {
        val target = EmailRules.backTarget(step, isBound) ?: return false
        epoch++; busy = false; error = ""; code = ""
        when (step) {
            EmailStep.Code -> password = "" // 「更换邮箱」：保留邮箱、清空密码
            else -> { email = ""; password = "" }
        }
        step = target
        return true
    }

    // —— 发送验证码（绑定 / 更换共用）——
    fun submitSend() {
        val addr = email.trim()
        if (!EmailRules.canSend(addr, password, busy)) return
        if (!EmailRules.isPlausible(addr)) { error = Str.s(R.string.email_err_invalid); return }
        val pw = password
        op("send_code", arrayOf("to" to EmailRules.maskForLog(addr)), { client.email.sendCode(addr, pw, EmailRules.lang()) }, ::onSendFail) { r ->
            if (step != EmailStep.Form) return@op
            email = addr; sentTo = addr; code = ""
            resendAt = EmailRules.resendDeadline(SystemClock.elapsedRealtime(), r.resendAfter.takeIf { it > 0 } ?: EmailRules.DEFAULT_RESEND_SEC)
            step = EmailStep.Code; focusTick++
        }
    }

    /** 「重新发送」：倒计时未走完不发；沿用验证码页上的邮箱与内存里的密码。 */
    fun resend() {
        if (busy || step != EmailStep.Code || password.isEmpty()) return
        if (EmailRules.secondsLeft(resendAt, SystemClock.elapsedRealtime()) > 0) return
        val (addr, pw) = sentTo to password
        op("resend_code", arrayOf("to" to EmailRules.maskForLog(addr)), { client.email.sendCode(addr, pw, EmailRules.lang()) }, ::onSendFail) { r ->
            code = ""
            resendAt = EmailRules.resendDeadline(SystemClock.elapsedRealtime(), r.resendAfter.takeIf { it > 0 } ?: EmailRules.DEFAULT_RESEND_SEC)
            focusTick++
        }
    }

    private fun onSendFail(e: ApiException): Boolean {
        // 间隔类限流带 retry_after_sec：重发倒计时按它重建，红字照常显示
        if (EmailRules.kindOf(e.code) == EmailErrorKind.TooFrequent && step == EmailStep.Code) rebuildCountdown(e)
        return false
    }

    // —— 验证 ——
    fun submitCode() {
        if (busy || step != EmailStep.Code || !TwoStepRules.isTotpComplete(code)) return
        val (addr, c) = sentTo to code
        op("verify", arrayOf("to" to EmailRules.maskForLog(addr)), { client.email.verify(addr, c, EmailRules.lang()) }, ::onVerifyFail) { r ->
            if (step != EmailStep.Code) return@op
            applyStatus(EmailStatus(status?.available ?: true, true, r.email.ifEmpty { addr }, r.verifiedAt))
            clearForm(); step = EmailStep.Bound
            toast = Str.s(R.string.email_toast_bound)
        }
    }

    private fun onVerifyFail(e: ApiException): Boolean {
        when (EmailRules.kindOf(e.code)) {
            EmailErrorKind.CodeWrong -> {
                // 已满 6 位被拒，留着无意义：清空并重新聚焦
                error = EmailRules.message(EmailErrorKind.CodeWrong, EmailApi.intField(e, "attempts_left")).orEmpty()
                code = ""; focusTick++
                return true
            }
            EmailErrorKind.CodeExpired -> { rebuildCountdown(e); code = ""; return false } // 码已作废，留着无意义
            EmailErrorKind.TooFrequent -> { rebuildCountdown(e); return false }
            else -> return false
        }
    }

    private fun rebuildCountdown(e: ApiException) {
        val sec = EmailApi.intField(e, "retry_after_sec") ?: return
        resendAt = EmailRules.resendDeadline(SystemClock.elapsedRealtime(), sec)
    }

    // —— 解绑 ——
    /** 点「解除绑定」：先弹系统确认，确认后才发请求。 */
    fun askUnbind() { if (!busy && password.isNotEmpty()) confirmUnbind = true }

    fun runUnbind() {
        confirmUnbind = false
        if (busy || password.isEmpty()) return
        val pw = password
        op("unbind", emptyArray(), { client.email.unbind(pw) }, ::onUnbindFail) {
            if (step != EmailStep.Unbind) return@op
            applyStatus(EmailStatus(status?.available ?: true, false, "", 0))
            clearForm(); step = EmailStep.Intro
            toast = Str.s(R.string.email_toast_unbound)
        }
    }

    private fun onUnbindFail(e: ApiException): Boolean {
        if (EmailRules.kindOf(e.code) != EmailErrorKind.NotBound) return false
        // 别的端已解绑：重取状态并回说明页，同时给出提示
        error = ""; clearForm(); step = EmailStep.Intro
        applyStatus(status?.copy(bound = false, email = "", verifiedAt = 0))
        toast = Str.s(R.string.email_err_not_bound)
        return true
    }

    private fun applyStatus(s: EmailStatus?) { status = s; statusRev++ }

    private fun clearForm() { email = ""; password = ""; code = ""; error = ""; sentTo = "" }

    /**
     * 统一请求壳：忙态防重入、挂 `client.scope`、失败翻成红字。[onFail] 返回 true = 调用方已处理（不再显示通用红字）。
     * 回写前比对 [epoch]，用户已返回 / 离开则丢弃结果。
     */
    private fun <T> op(
        what: String,
        fields: Array<Pair<String, Any?>>,
        call: suspend () -> T,
        onFail: (ApiException) -> Boolean,
        onOk: (T) -> Unit,
    ) {
        if (busy) return
        busy = true; error = ""
        val mine = epoch
        client.scope.launch {
            try {
                val r = call()
                log.i("email_${what}_ok", *fields)
                if (mine == epoch) onOk(r) else if (what == "verify" || what == "unbind") refresh() // 服务端已生效、用户已离开：补取最新状态
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w("email_${what}_failed", "err" to e.javaClass.simpleName, "code" to ((e as? ApiException)?.code ?: 0))
                if (mine == epoch && (e !is ApiException || !onFail(e))) error = messageFor(e)
            } finally {
                if (mine == epoch) busy = false
            }
        }
    }

    private fun messageFor(e: Throwable): String {
        val api = e as? ApiException ?: return e.userMessage(Str.s(R.string.net_error_unreachable))
        val text = EmailRules.message(EmailRules.kindOf(api.code), EmailApi.intField(api, "attempts_left"), EmailApi.intField(api, "retry_after_sec"))
        return text ?: e.userMessage(Str.s(R.string.net_error_unreachable))
    }
}
