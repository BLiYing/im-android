package com.libeyond.imandroid.sdk.api

/**
 * 回包里没给（或给了非正数）`resend_after` 时的重发间隔（秒）。**唯一一份**：短信（登录 / 重设 / 绑定 / 本人确认）
 * 与邮箱验证码都用它，服务端的每号码 / 每邮箱间隔也是 60 秒。
 */
const val DEFAULT_RESEND_SEC = 60

/** 发码类接口的回包：多久后可以重发（[SmsSendResult] / [VerifyCodeResult] / [EmailSendResult]）。 */
interface ResendHint {
    val resendAfter: Int

    /** 实际用来起倒计时的秒数：回包的正数值，否则 [DEFAULT_RESEND_SEC]。 */
    val resendAfterSec: Int get() = resendAfter.takeIf { it > 0 } ?: DEFAULT_RESEND_SEC
}
