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
import com.libeyond.imandroid.sdk.api.EmailSendResult
import com.libeyond.imandroid.sdk.api.EmailStatus
import com.libeyond.imandroid.sdk.api.Proof
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「隐私与安全 ▸ 邮箱登录」的状态与动作（Screen 纯展示，CODING_STYLE §7②）。
 * 由 `PrivacySecurityHost` 持有，请求挂 `client.scope`。
 *
 * 身份证明（密码或短信验证码）在确认身份弹层里输（[proof]，PHONE_AUTH_DESIGN §7.2）。
 * 用密码证明时 Form → Code 之后**仍保留**（「重新发送」要再带一次）；短信验证码一个码只能用一次，
 * 重发时重新弹层。回到 Form（更换邮箱）/ 成功 / 离开页面即清。**日志里不写密码、验证码，邮箱脱敏**。
 */
class EmailController(private val client: IMClient, sec: SecurityState) {
    private val log = IMLog.tag("IM.Email")

    var step by mutableStateOf(EmailStep.Intro); private set
    /** 已取到的状态；null = 还没取到（或一直失败）。失败时保留上一次的值。 */
    var status by mutableStateOf<EmailStatus?>(null); private set

    var email by mutableStateOf(""); private set
    /** 确认身份弹层；Host 画它。 */
    val proof = ProofController.of(client, sec)
    /** 上次 send-code 成功用的密码证明（重发时复用）；短信证明用完即作废，不留。 */
    private var reusableProof: Proof? = null
    var code by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    /** 无身份证明的请求（验证、用内存里的密码重发）在途；带证明的在 [proof] 里。 */
    private var running by mutableStateOf(false)
    val busy: Boolean get() = running || proof.busy
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
        epoch++; running = false; email = ""; reusableProof = null; proof.clear(); code = ""; error = ""; sentTo = ""; confirmUnbind = false
        step = EmailRules.homeStep(status)
    }

    fun onEmail(v: String) { email = v; error = "" }

    /** 验证码输入；输满 6 位自动提交（复用两步验证的清洗与判定）。 */
    fun onCode(v: String) {
        code = TwoStepRules.sanitizeCode(v); error = ""
        if (step == EmailStep.Code && TwoStepRules.shouldAutoSubmit(code, busy)) submitCode()
    }

    fun startForm() { epoch++; running = false; email = ""; reusableProof = null; code = ""; error = ""; step = EmailStep.Form }
    fun startUnbind() { epoch++; running = false; reusableProof = null; error = ""; step = EmailStep.Unbind }

    /** 返回键：true = 已在内部处理；false = 应退出邮箱登录回隐私页。 */
    fun back(): Boolean {
        val target = EmailRules.backTarget(step, isBound) ?: return false
        epoch++; running = false; error = ""; code = ""
        reusableProof = null
        if (step != EmailStep.Code) email = "" // 「更换邮箱」：保留邮箱
        proof.clear()
        step = target
        return true
    }

    // —— 发送验证码（绑定 / 更换共用）——
    fun submitSend() {
        val addr = email.trim()
        if (!EmailRules.canSend(addr, busy)) return
        if (!EmailRules.isPlausible(addr)) { error = Str.s(R.string.email_err_invalid); return }
        withProof("send_code", addr, { p -> client.email.sendCode(addr, p, EmailRules.lang()) }, ::onSendFail) { p, r ->
            reusableProof = p as? Proof.Password
            if (step != EmailStep.Form) return@withProof
            email = addr; sentTo = addr; code = ""
            resendAt = EmailRules.resendDeadline(SystemClock.elapsedRealtime(), r.resendAfterSec)
            step = EmailStep.Code; focusTick++
        }
    }

    /** 「重新发送」：倒计时未走完不发；沿用验证码页上的邮箱，与内存里的密码证明（用的是短信验证码则重新弹层）。 */
    fun resend() {
        if (busy || step != EmailStep.Code) return
        if (EmailRules.secondsLeft(resendAt, SystemClock.elapsedRealtime()) > 0) return
        val addr = sentTo
        val reuse = reusableProof
        val call: suspend (Proof) -> EmailSendResult = { p -> client.email.sendCode(addr, p, EmailRules.lang()) }
        if (reuse != null) op("resend_code", arrayOf("to" to EmailRules.maskForLog(addr)), { call(reuse) }, ::onSendFail) { afterResend(reuse, it) }
        else withProof("resend_code", addr, call, ::onSendFail, ::afterResend)
    }

    private fun afterResend(p: Proof, r: EmailSendResult) {
        reusableProof = p as? Proof.Password
        code = ""
        resendAt = EmailRules.resendDeadline(SystemClock.elapsedRealtime(), r.resendAfterSec)
        focusTick++
    }

    /** 页面上的发码失败（证明本身的错已留在确认身份弹层里）。 */
    private fun onSendFail(e: ApiException): Boolean {
        if (e.code == ErrCode.WRONG_PASSWORD) reusableProof = null // 内存里的密码已不对（别处改过）
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
    fun askUnbind() { if (!busy) confirmUnbind = true }

    /** 确认解绑后弹确认身份弹层，证明通过才发请求。 */
    fun runUnbind() {
        confirmUnbind = false
        if (busy) return
        withProof("unbind", null, { p -> client.email.unbind(p) }, ::onUnbindFail) { _, _ ->
            if (step != EmailStep.Unbind) return@withProof
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

    private fun clearForm() { email = ""; reusableProof = null; code = ""; error = ""; sentTo = "" }

    /**
     * 统一请求壳：忙态防重入、挂 `client.scope`、失败翻成红字。[onFail] 返回 true = 调用方已处理（不再显示通用红字）。
     * 回写前比对 [epoch]，用户已返回 / 离开则丢弃结果。
     */
    /**
     * 带身份证明的请求（[ProofController.submit]）：证明本身的错留在弹层里；其余收起弹层（验证码保留）后交 [onFail]，
     * 返回 false 再显示通用红字。回写前比对 [epoch]（打开弹层时的），用户已离开则丢弃结果。
     */
    private fun <T> withProof(
        what: String,
        addr: String?,
        call: suspend (Proof) -> T,
        onFail: (ApiException) -> Boolean,
        onOk: (Proof, T) -> Unit,
    ) {
        if (busy) return
        error = ""
        val mine = epoch
        val fields: Array<Pair<String, Any?>> = if (addr == null) emptyArray() else arrayOf("to" to EmailRules.maskForLog(addr))
        proof.submit(
            call = { p -> p to call(p) },
            onOk = { (p, r) ->
                log.i("email_${what}_ok", *fields)
                if (mine == epoch) onOk(p, r) else if (what == "unbind") client.scope.launch { refresh() } // 服务端已生效、用户已离开：补取最新状态
            },
            onPageError = { e ->
                log.w("email_${what}_failed", "err" to e.javaClass.simpleName, "code" to ((e as? ApiException)?.code ?: 0))
                if (mine == epoch && (e !is ApiException || !onFail(e))) error = messageFor(e)
            },
        )
    }

    private fun <T> op(
        what: String,
        fields: Array<Pair<String, Any?>>,
        call: suspend () -> T,
        onFail: (ApiException) -> Boolean,
        onOk: (T) -> Unit,
    ) {
        if (busy) return
        running = true; error = ""
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
                if (mine == epoch) running = false
            }
        }
    }

    private fun messageFor(e: Throwable): String {
        val api = e as? ApiException ?: return e.userMessage(Str.s(R.string.net_error_unreachable))
        val text = EmailRules.message(EmailRules.kindOf(api.code), EmailApi.intField(api, "attempts_left"), EmailApi.intField(api, "retry_after_sec"))
        return text ?: e.userMessage(Str.s(R.string.net_error_unreachable))
    }
}
