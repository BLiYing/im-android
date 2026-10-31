package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.EmailStatus
import com.libeyond.imandroid.sdk.protocol.ErrCode

/** 邮箱登录页的步骤（隐私页 ▸ 邮箱登录 内部导航）。 */
enum class EmailStep { Intro, Form, Code, Bound, Unbind }

/** 错误分类：按业务码分，**不 parse 服务端文案**。 */
enum class EmailErrorKind {
    WrongPassword, Invalid, Taken, CodeWrong, CodeExpired, NotBound, Same, Unavailable, SendFailed, TooFrequent, Other
}

/**
 * 邮箱登录的纯规则（可单测；设计 `IMServer/docs/design/EMAIL_LOGIN_DESIGN.md`）。
 * 6 位验证码的清洗 / 自动提交判定**复用 [TwoStepRules]**，这里不另写一套。
 */
object EmailRules {
    /** 邮箱总长上限（与服务端 §3.4 一致；权威校验在服务端）。 */
    const val MAX_LEN = 254
    /** `send-code` 回包没带 `resend_after` 时的重发间隔（秒）。 */
    const val DEFAULT_RESEND_SEC = 60
    /** 验证码错时，剩余次数 ≤ 此值才提示。 */
    const val ATTEMPTS_HINT_MAX = 2

    /**
     * 本地轻量预检：恰一个 `@`、`@` 前非空、`@` 后含 `.`、无空白、长度 ≤ 254。
     * 只拦明显手误；非 ASCII / 各 label 长度等一律交给服务端（`200030`）。
     */
    fun isPlausible(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s.length > MAX_LEN || s.any { it.isWhitespace() }) return false
        val at = s.indexOf('@')
        if (at <= 0 || at != s.lastIndexOf('@')) return false
        return '.' in s.substring(at + 1)
    }

    /** 「发送验证码」按钮可点：两格都有内容（格式不合规留到点击后红字，不在这里置灰）。 */
    fun canSend(email: String, password: String, busy: Boolean): Boolean =
        !busy && email.isNotBlank() && password.isNotEmpty()

    /** 请求语言：`en` / `zh-Hans`（与 App 当前文案同源）。 */
    fun lang(): String = if (Str.languageTag == "en") "en" else "zh-Hans"

    /** 日志里的邮箱：`a***@gmail.com`（local 留首字符；只有 1 位则整个 `*`）。没有 `@` 返回 `***`。 */
    fun maskForLog(email: String): String {
        val at = email.indexOf('@')
        if (at < 0) return "***"
        val local = email.substring(0, at)
        return (if (local.length <= 1) "*" else local.take(1) + "***") + email.substring(at)
    }

    // —— 重发倒计时（LaunchedEffect 按 deadline 重算，离开页面再回来仍准确）——

    /** 以「现在 + [sec] 秒」为重发时刻；[sec] ≤ 0 = 现在就能重发。 */
    fun resendDeadline(nowMs: Long, sec: Int): Long = nowMs + maxOf(sec, 0) * 1000L

    /** 距 [deadlineMs] 还剩几秒（向上取整，≤ 0 记 0）。 */
    fun secondsLeft(deadlineMs: Long, nowMs: Long): Int {
        val ms = deadlineMs - nowMs
        return if (ms <= 0) 0 else ((ms + 999) / 1000).toInt()
    }

    /** 「重新发送」文字链的文案：倒计时中带秒数。 */
    fun resendLabel(left: Int): String =
        if (left > 0) Str.s(R.string.email_code_resend_wait, left) else Str.s(R.string.email_code_resend)

    // —— 导航 ——

    /** 返回键去向；null = 退出邮箱登录（回隐私页）。Code 回 Form（保留邮箱、清密码，由 Controller 做）。 */
    fun backTarget(step: EmailStep, bound: Boolean): EmailStep? = when (step) {
        EmailStep.Intro, EmailStep.Bound -> null
        EmailStep.Form -> if (bound) EmailStep.Bound else EmailStep.Intro
        EmailStep.Code -> EmailStep.Form
        EmailStep.Unbind -> EmailStep.Bound
    }

    /** 状态页应落在哪：已绑定 → Bound，否则 Intro。 */
    fun homeStep(status: EmailStatus?): EmailStep = if (status?.bound == true) EmailStep.Bound else EmailStep.Intro

    /** 隐私页那一行的右值：没取到（null）留空；已绑定显邮箱，否则「未绑定」。 */
    fun rowLabel(status: EmailStatus?): String = when {
        status == null -> ""
        status.bound && status.email.isNotEmpty() -> status.email
        else -> Str.s(R.string.email_status_unbound)
    }

    /** 说明页主按钮是否置灰：服务端明确 `available:false` 才灰（没取到当可用，由服务端 `200036` 兜底）。 */
    fun introDisabled(status: EmailStatus?): Boolean = status != null && !status.available

    // —— 错误码 ——

    fun kindOf(code: Int): EmailErrorKind = when (code) {
        ErrCode.WRONG_PASSWORD -> EmailErrorKind.WrongPassword
        ErrCode.EMAIL_INVALID -> EmailErrorKind.Invalid
        ErrCode.EMAIL_TAKEN -> EmailErrorKind.Taken
        ErrCode.EMAIL_CODE_INVALID -> EmailErrorKind.CodeWrong
        ErrCode.EMAIL_CODE_EXPIRED -> EmailErrorKind.CodeExpired
        ErrCode.EMAIL_NOT_BOUND -> EmailErrorKind.NotBound
        ErrCode.EMAIL_UNCHANGED -> EmailErrorKind.Same
        ErrCode.EMAIL_UNAVAILABLE -> EmailErrorKind.Unavailable
        ErrCode.EMAIL_SEND_FAILED -> EmailErrorKind.SendFailed
        ErrCode.RATE_LIMITED -> EmailErrorKind.TooFrequent
        else -> EmailErrorKind.Other
    }

    /** 文案；null = 没有专门文案，调用方用通用兜底。 */
    fun message(kind: EmailErrorKind, attemptsLeft: Int? = null, retryAfterSec: Int? = null): String? = when (kind) {
        EmailErrorKind.WrongPassword -> Str.s(R.string.email_err_password)
        EmailErrorKind.Invalid -> Str.s(R.string.email_err_invalid)
        EmailErrorKind.Taken -> Str.s(R.string.email_err_taken)
        EmailErrorKind.CodeWrong ->
            if (attemptsLeft != null && attemptsLeft <= ATTEMPTS_HINT_MAX) Str.s(R.string.email_code_wrong_left, maxOf(attemptsLeft, 0))
            else Str.s(R.string.email_code_wrong)
        EmailErrorKind.CodeExpired -> Str.s(R.string.email_code_expired)
        EmailErrorKind.NotBound -> Str.s(R.string.email_err_not_bound)
        EmailErrorKind.Same -> Str.s(R.string.email_err_same)
        EmailErrorKind.Unavailable -> Str.s(R.string.email_err_unavailable)
        EmailErrorKind.SendFailed -> Str.s(R.string.email_err_send_failed)
        EmailErrorKind.TooFrequent ->
            if (retryAfterSec != null && retryAfterSec > 0) Str.s(R.string.email_err_too_frequent_secs, retryAfterSec)
            else Str.s(R.string.email_err_too_frequent)
        EmailErrorKind.Other -> null
    }
}
