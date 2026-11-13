package com.libeyond.imandroid.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.data.SmsErrors
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.PhoneAuthApi
import com.libeyond.imandroid.sdk.api.ResendHint
import com.libeyond.imandroid.sdk.http.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 发码 + 重发倒计时（短信四处共用：确认身份弹层、设置 / 修改密码、绑定手机号、登录页两张表）。
 *
 * - 成功：按回包 [ResendHint.resendAfterSec] 起倒计时；
 * - 失败：`100002` / `200044` 带 `retry_after_sec` 时按它重建（[SmsErrors.countdownAfterFailure]），失败交调用方出文案；
 * - **按号码计**：[follow] 换了号码就清掉本地截止时刻（服务端的 60 秒间隔是按号码算的，换个号不该等上一个号的倒计时）。
 *
 * 截止时刻用 [now]（默认 `SystemClock.elapsedRealtime`，单测注入）为基准；界面用 `rememberSecondsLeft(deadline)` 每秒重算。
 */
class ResendTimer(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {
    var deadline by mutableLongStateOf(0L); private set
    var sentOnce by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    /** 倒计时属于哪个号码（`国家码:号码`）；null = 还没绑定。 */
    private var owner: String? = null

    fun secondsLeft(): Int = EmailRules.secondsLeft(deadline, now())

    /** 号码变了：清掉上一个号码的截止时刻与「已发过」。同一个号码不动。 */
    fun follow(target: String) {
        if (target == owner) return
        owner = target; deadline = 0L; sentOnce = false
    }

    /**
     * 发一次码。在途或还在倒计时返回 false（什么也不做）；否则立刻置 [sending]（防连点）、在 [scope] 里发，
     * 结束后回调 [onDone]（null = 成功）。
     */
    fun send(scope: CoroutineScope, call: suspend () -> ResendHint, onDone: (Throwable?) -> Unit = {}): Boolean {
        if (sending || secondsLeft() > 0) return false
        sending = true
        scope.launch {
            val r = try { runCatchingCancellable { call() } } finally { sending = false }
            r.onSuccess { sentOnce = true; deadline = EmailRules.resendDeadline(now(), it.resendAfterSec) }
                .onFailure { e -> (e as? ApiException)?.let(::afterFailure) }
            onDone(r.exceptionOrNull())
        }
        return true
    }

    /** 失败响应带 `retry_after_sec`（间隔限流 / 码作废）时按它重建倒计时；其余不动。 */
    fun afterFailure(e: ApiException) {
        SmsErrors.countdownAfterFailure(e.code, retryAfterSec(e))?.let { deadline = EmailRules.resendDeadline(now(), it) }
    }
}

/** 失败响应里的 `retry_after_sec`（没有返回 null）。 */
internal fun retryAfterSec(e: ApiException): Int? = PhoneAuthApi.intField(e, "retry_after_sec")

/** 发码失败的文案（发码路径才带秒数：「发送过于频繁，请 N 秒后再试」）。 */
internal fun sendFailureText(e: Throwable): String {
    val api = e as? ApiException
    return api?.let { SmsErrors.message(it.code, retryAfterSec = retryAfterSec(it)) }
        ?: e.userMessage(Str.s(R.string.net_error_unreachable))
}

/** 带验证码的提交失败的文案（`200043` 带剩余次数；`100002` 用通用「操作太频繁」）。 */
internal fun submitFailureText(e: Throwable): String {
    val api = e as? ApiException
    return api?.let { SmsErrors.message(it.code, attemptsLeft = PhoneAuthApi.intField(it, "attempts_left")) }
        ?: e.userMessage(Str.s(R.string.net_error_unreachable))
}

/**
 * 「国家码 + 手机号 + 验证码 + 发码倒计时」一组输入：登录页的验证码登录与忘记密码各一份（`PhoneCodeFields` 画它）。
 * 倒计时跟着号码走（[ResendTimer.follow]）。
 */
class PhoneCodeForm(now: () -> Long = { SystemClock.elapsedRealtime() }) {
    var countryCode by mutableStateOf(PhoneAuthRules.DEFAULT_COUNTRY_CODE); private set
    var phone by mutableStateOf(""); private set
    var code by mutableStateOf(""); private set
    val timer = ResendTimer(now)

    /** 去掉空格 / 短横线后的号码。 */
    val normalizedPhone: String get() = PhoneAuthRules.normalizePhone(phone)

    fun onCountryCode(v: String) { countryCode = v; timer.follow(key()) }
    fun onPhone(v: String) { phone = v; timer.follow(key()) }
    fun onCode(v: String) { code = PhoneAuthRules.sanitizeCode(v) }
    fun clearCode() { code = "" }

    /** 发码（[call] 收到国家码与规范化号码）；号码为空或在倒计时 / 在途时返回 false。 */
    fun send(scope: CoroutineScope, call: suspend (cc: String, phone: String) -> ResendHint, onDone: (Throwable?) -> Unit): Boolean {
        val (cc, p) = countryCode to normalizedPhone
        if (p.isEmpty()) return false
        timer.follow(key())
        return timer.send(scope, { call(cc, p) }, onDone)
    }

    private fun key() = countryCode + ":" + normalizedPhone
}
