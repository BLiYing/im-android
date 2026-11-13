package com.libeyond.imandroid.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.data.ProofTab
import com.libeyond.imandroid.data.SmsErrors
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.PhoneAuthApi
import com.libeyond.imandroid.sdk.api.Proof
import com.libeyond.imandroid.sdk.api.SecurityStatus
import com.libeyond.imandroid.sdk.api.VerifyCodeResult
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.IdentityProofSheet
import com.libeyond.imandroid.ui.components.rememberSecondsLeft
import com.libeyond.imandroid.ui.components.sendCodeLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * `GET /users/me/security` 的结果。一个宿主一份：隐私与安全页下的密码页、手机号页、两步验证、邮箱共用一份；
 * 注销 / 导出各自一份。取不到时保留上一次的值（null = 从没取到）。
 *
 * 本人号码的 `verify` 验证码倒计时也放这里（[verifyTimer]）：同一个号码，几个页面共用一份，换页不重置。
 */
class SecurityState(
    private val fetch: suspend () -> SecurityStatus,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var status by mutableStateOf<SecurityStatus?>(null); private set
    val verifyTimer = ResendTimer(now)
    /** 「最近验证过免确认」到期时刻（[now] 基准）：取到 security 时按 `recent_auth_sec` 算，本地已知的变化走 [setRecentAuth]。 */
    private var recentAuthUntil = 0L

    /** 重取；失败返回异常（[status] 不动）。 */
    suspend fun refresh(): Throwable? =
        runCatchingCancellable { fetch() }
            .onSuccess { status = it; setRecentAuth(it.recentAuthSec) }
            .onFailure { IMLog.tag("IM.Security").w("security_status_failed", "code" to ((it as? ApiException)?.code ?: 0)) }
            .exceptionOrNull()

    /** 本地已知的变化（刚设了密码 / 绑了手机）先改上，不等重取。 */
    fun update(f: (SecurityStatus) -> SecurityStatus) { status = status?.let(f) }

    /** 「最近验证过免确认」还剩几秒（没取到 = 0）。 */
    fun recentAuthLeft(): Int = ((recentAuthUntil - now()) / 1000).coerceAtLeast(0).toInt()

    /** 免确认从现在起还剩 [sec] 秒：取到 security / 刚用证明通过（服务端已记下）/ 服务端说要确认（`200049`，记 0）。 */
    fun setRecentAuth(sec: Int, from: Long = now()) { recentAuthUntil = from + sec.coerceAtLeast(0) * 1000L }

    /** 当前时刻（[now] 基准），给 [setRecentAuth] 的 `from` 用。 */
    fun clock(): Long = now()

    companion object {
        fun of(client: IMClient) = SecurityState({ client.security.status() })
    }
}

/**
 * 确认身份弹层的状态、发码与提交（PHONE_AUTH_DESIGN §7）。
 *
 * 统一入口 [submit]：弹层 → 用户确认 → 请求 → 成功先 [succeeded] 再回调；失败先 [reject]（证明本身的问题留在弹层里重输），
 * 不是证明的问题就 [dismiss] 再交给页面。
 *
 * 「最近验证过免确认」（PHONE_AUTH_DESIGN §15.2）：[submit] 取到状态后若免确认还剩 ≥ 15 秒（[PhoneAuthRules.canSkipProof]）
 * **不弹层**，直接用 [Proof.None] 发请求；回 `200049`（边界上刚好过期）再正常弹层、用用户给的证明重发。
 *
 * 页签按 [SecurityState.status] 决定（[PhoneAuthRules.proofTabs]）：有旧值就**立即**用旧值弹出、后台重取校正；
 * 一次都没取到时先取（[opening] 忙态），取不到**不弹层**、给 [openFailure] 提示——退化成只有密码页签会把没设密码的账号卡死。
 *
 * 密码与验证码只在内存：[succeeded] 全清；[dismiss]（取消 / 页面错误）清密码、**保留验证码**——服务端「群主没转让」
 * 这类页面错误不会用掉验证码（PROTOCOL §7.3），用户处理完回来可以直接再提交；离开页面 [clear] 全清。
 * **日志里不写密码、验证码、号码**。
 */
class ProofController(
    private val scope: CoroutineScope,
    private val sec: SecurityState,
    private val requestCode: suspend () -> VerifyCodeResult,
) {
    private val log = IMLog.tag("IM.Proof")

    var visible by mutableStateOf(false); private set
    var tabs by mutableStateOf(listOf(ProofTab.Password)); private set
    var tab by mutableStateOf(ProofTab.Password); private set
    var password by mutableStateOf(""); private set
    var code by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    /** 没有可用的旧状态、正在取 `/users/me/security`。 */
    var opening by mutableStateOf(false); private set
    /** [submit] 发起的请求在途。 */
    var submitting by mutableStateOf(false); private set
    /** 取不到状态没能弹出时的一次性提示（[ProofSheetHost] 吐司）。 */
    var openFailure by mutableStateOf<String?>(null)
    private var onProof: ((Proof) -> Unit)? = null

    val timer: ResendTimer get() = sec.verifyTimer
    val phoneMasked: String get() = sec.status?.phoneMasked.orEmpty()
    /** 宿主的按钮该显示忙态：在取状态或在提交。 */
    val busy: Boolean get() = opening || submitting

    /**
     * 统一入口：取到证明后调 [call]（挂在本控制器的 scope，请求途中离开页面不取消）；
     * 成功 → [succeeded] 后 [onOk]；证明错 → 留在弹层；其余 → [dismiss]（验证码保留）后 [onPageError]。
     * 免确认窗口内先不弹层、用 [Proof.None] 直接发（见类注释）。
     */
    fun <T> submit(call: suspend (Proof) -> T, onOk: (T) -> Unit, onPageError: (Throwable) -> Unit) {
        lateinit var attempt: (Proof) -> Unit
        attempt = { p ->
            if (!submitting) {
                submitting = true; error = ""
                scope.launch {
                    // 服务端的免确认窗口从校验证明时起算：按发请求的时刻记，别把请求耗时算进本地窗口
                    val sentAt = sec.clock()
                    val r = try { runCatchingCancellable { call(p) } } finally { submitting = false }
                    r.onSuccess {
                        if (p != Proof.None) sec.setRecentAuth(PhoneAuthRules.RECENT_AUTH_WINDOW_SEC, from = sentAt)
                        succeeded(); onOk(it)
                    }.onFailure { e ->
                        // 空证明回 200049（边界上刚好过期 / 服务端重启）：弹层重来；途中已离开页面（onProof 已清）则照常交页面
                        if (p == Proof.None && (e as? ApiException)?.code == ErrCode.PROOF_REQUIRED && onProof === attempt) fallBackToSheet(attempt)
                        else if (!reject(e)) { dismiss(); onPageError(e) }
                    }
                }
            }
        }
        open(allowSkip = true, then = attempt)
    }

    private fun fallBackToSheet(attempt: (Proof) -> Unit) {
        log.i("proof_required_fallback")
        sec.setRecentAuth(0)
        onProof = null
        open(then = attempt)
    }

    /**
     * 打开弹层；用户点「确认」时回调 [then]。已在打开 / 已显示 / 在提交时忽略。
     * 取状态：有旧值立即用（弹出后后台重取校正页签），没有先取；取不到不弹层、给 [openFailure]。
     * [allowSkip] 且在免确认窗口内（[PhoneAuthRules.canSkipProof]）→ 不弹层，直接回调 [Proof.None]。
     */
    fun open(allowSkip: Boolean = false, then: (Proof) -> Unit) {
        if (busy || visible) return
        onProof = then; error = ""; openFailure = null
        sec.status?.let { cached ->
            // 刚在别处设了密码 / 绑了手机：后台重取，页签跟着校正
            if (proceed(cached, then, allowSkip)) scope.launch { if (sec.refresh() == null && visible) sec.status?.let(::applyTabs) }
            return
        }
        opening = true
        scope.launch {
            val failure = try { sec.refresh() } finally { opening = false }
            val st = sec.status
            when {
                onProof !== then -> Unit // 取的途中已离开页面（[clear]）
                st != null -> proceed(st, then, allowSkip)
                else -> {
                    onProof = null
                    openFailure = failure?.userMessage(Str.s(R.string.net_error_unreachable)) ?: Str.s(R.string.net_error_unreachable)
                }
            }
        }
    }

    /** 免确认 → 直接回调空证明（返回 false）；否则弹层（返回 true）。 */
    private fun proceed(st: SecurityStatus, then: (Proof) -> Unit, allowSkip: Boolean): Boolean {
        if (allowSkip && PhoneAuthRules.canSkipProof(sec.recentAuthLeft())) {
            log.i("proof_skipped_recent_auth")
            then(Proof.None)
            return false
        }
        show(st)
        return true
    }

    private fun show(st: SecurityStatus) { applyTabs(st); visible = true }

    private fun applyTabs(st: SecurityStatus) {
        tabs = PhoneAuthRules.proofTabs(st)
        if (tab !in tabs) tab = tabs.firstOrNull() ?: tab // 空 = 没有可用方式：tab 无意义，弹层不画输入
    }

    fun selectTab(t: ProofTab) { if (t in tabs) { tab = t; error = "" } }
    fun onPassword(v: String) { password = v; error = "" }
    fun onCode(v: String) { code = PhoneAuthRules.sanitizeCode(v); error = "" }

    fun confirm() {
        if (tabs.isEmpty() || !PhoneAuthRules.canConfirmProof(tab, password, code, busy = submitting)) return
        error = ""
        onProof?.invoke(if (tab == ProofTab.Password) Proof.Password(password) else Proof.SmsCode(code))
    }

    /** 发验证码到本人号码（`verify` 场景）。 */
    fun sendCode() {
        error = ""
        timer.send(scope, {
            requestCode().also { r -> if (r.phoneMasked.isNotEmpty()) sec.update { it.copy(phoneMasked = r.phoneMasked) } }
        }) { e ->
            if (e == null) log.i("verify_code_sent")
            else { log.w("verify_code_failed", "code" to ((e as? ApiException)?.code ?: 0)); error = sendFailureText(e) }
        }
    }

    /**
     * 提交失败：证明本身的问题（[SmsErrors.isProofError]）留在弹层里显示并返回 true；其余返回 false（弹层不动）。
     * 一般不直接调，走 [submit]。
     */
    fun reject(e: Throwable): Boolean {
        val api = e as? ApiException ?: return false
        if (!visible || !SmsErrors.isProofError(api.code, PhoneAuthApi.errorField(api))) return false
        if (api.code == ErrCode.SMS_CODE_EXPIRED) code = "" // 码已作废，留着无意义
        error = submitFailureText(api)
        return true
    }

    /** 请求成功：收起并清掉输入。 */
    fun succeeded() { visible = false; password = ""; code = ""; error = ""; onProof = null }

    /**
     * 用户关弹层（「取消」/ 下滑 / 点外部 / 返回键）。**提交中忽略**：请求结果回来时弹层必须还在，
     * 证明本身的错才能落在弹层里（否则会被当成页面错误、过期码也不清）。
     */
    fun cancel() { if (!submitting) dismiss() }

    /** 页面错误 / 取消时收起：清密码，验证码保留（见类注释）。 */
    fun dismiss() { visible = false; password = ""; error = "" }

    /** 离开页面：全清（含保留的验证码），在途的「打开」不再弹出。 */
    fun clear() { dismiss(); code = ""; onProof = null; openFailure = null }

    companion object {
        fun of(client: IMClient, sec: SecurityState) =
            ProofController(client.scope, sec) { client.security.verifyCode(EmailRules.lang()) }
    }
}

/** 弹层本体的接线（含「取不到状态」的吐司）。 */
@Composable
fun ProofSheetHost(ctl: ProofController) {
    ctl.openFailure?.let { IMToast(it) { ctl.openFailure = null } }
    if (!ctl.visible) return
    val left = rememberSecondsLeft(ctl.timer.deadline)
    IdentityProofSheet(
        tabs = ctl.tabs, tab = ctl.tab, onTab = ctl::selectTab,
        password = ctl.password, onPassword = ctl::onPassword,
        code = ctl.code, onCode = ctl::onCode,
        phoneMasked = ctl.phoneMasked,
        sendText = sendCodeLabel(left, ctl.timer.sentOnce), sendEnabled = left <= 0 && !ctl.timer.sending, onSend = ctl::sendCode,
        error = ctl.error, busy = ctl.submitting,
        onConfirm = ctl::confirm, onDismiss = ctl::cancel,
    )
}
