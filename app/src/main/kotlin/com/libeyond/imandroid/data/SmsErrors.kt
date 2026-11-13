package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 短信验证码相关错误的文案与落点（PROTOCOL §7.3 / §8）。
 *
 * 码 → 文案以 [ErrorText.friendlyRes] 为准（`200040`–`200048`、`100002` 都在那张表里），这里**只管带参数的两个分支**：
 * `200043` 带 `attempts_left` → `err.200043.left`；发码限流 `100002` 带 `retry_after_sec` → `sms.err.too_frequent_secs`。
 */
object SmsErrors {

    /**
     * 文案；不认识的码返回 null（调用方回退通用文案）。
     * [retryAfterSec] **只在发码失败时传**：提交时的 `100002` 是验证防爆破，用通用的「操作太频繁」。
     */
    fun message(code: Int, attemptsLeft: Int? = null, retryAfterSec: Int? = null): String? = when {
        code == ErrCode.SMS_CODE_INVALID && attemptsLeft != null && attemptsLeft >= 0 -> Str.s(R.string.err_200043_left, attemptsLeft)
        code == ErrCode.RATE_LIMITED && retryAfterSec != null && retryAfterSec > 0 -> Str.s(R.string.sms_err_too_frequent_secs, retryAfterSec)
        else -> ErrorText.friendlyRes(code)?.let { Str.s(it) }
    }

    /**
     * 发码失败后重发倒计时该从几秒开始：`100002`（间隔限流）/ `200044`（码作废）带 `retry_after_sec>0` 时用它；
     * 其余不动倒计时（返回 null）。
     */
    fun countdownAfterFailure(code: Int, retryAfterSec: Int?): Int? =
        if ((code == ErrCode.RATE_LIMITED || code == ErrCode.SMS_CODE_EXPIRED) && retryAfterSec != null && retryAfterSec > 0) retryAfterSec else null

    /**
     * 带身份证明的请求失败后，提示是否留在确认身份弹层里（true）还是交给页面（false）：
     * - 服务端给了 `data.field`（只有绑定手机号）就按它：`"proof"` 弹层，其余页面；
     * - 密码错 `200002`、验证码错 / 过期 `200043` / `200044`、本人没绑手机 `200040` 是证明本身的问题；
     * - `100002` **一律交给页面**（与 Web 对齐）：邮箱发信间隔 / 每日 / 每邮箱 / 每 IP 限额、动态码防爆破、验证防爆破都回它，
     *   带不带 `retry_after_sec` 都有；留在弹层里会把「这件事被限流」说成「你的证明错了」；
     * - 其余是页面上的问题（如邮箱被占用、群主没转让）。
     */
    fun isProofError(code: Int, field: String?): Boolean = when (field) {
        "proof" -> true
        null -> code == ErrCode.WRONG_PASSWORD || code == ErrCode.SMS_CODE_INVALID ||
            code == ErrCode.SMS_CODE_EXPIRED || code == ErrCode.SMS_UNAVAILABLE
        else -> false
    }
}
