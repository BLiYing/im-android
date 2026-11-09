package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 登录页「注册是否被管理员暂停」（S4，`GET /api/v1/public-config`）的判据。
 *
 * 状态只有一个：**明确关闭了注册的那台 host**（null = 不知道 / 开放）。结果按发起请求时的 host 记账，
 * 显示时与当前 host 比——改了服务器地址后，旧地址迟到的回包既不会把新地址标成关闭，也不会把它解禁。
 *
 * 两个来源喂同一份状态（状态在 `AppRoot`，`LoginScreen` 只读）：public-config 的 `register_enabled`，
 * 以及提交注册被服务端回 200007（[afterSubmitRejected]，等同该 host 的 `register_enabled=false`）。
 * 之后该 host 的下一次 public-config 回包仍可把它重新打开。
 */
object RegisterGate {
    /** 收到 [requestedHost] 的结果后新的「关闭的 host」。 */
    fun afterResult(closedFor: String?, requestedHost: String, enabled: Boolean): String? = when {
        !enabled -> requestedHost
        closedFor == requestedHost -> null
        else -> closedFor
    }

    /** 当前地址是否已确认关闭注册。 */
    fun isClosed(closedFor: String?, currentHost: String): Boolean =
        closedFor != null && closedFor == currentHost

    /**
     * 提交注册被拒（错误码 [code]）后新的「关闭的 host」；null = 这个错误与注册开关无关，状态不变、照常显示错误。
     * 非 null 时调用方**不再**把同一句 err_200007 放进普通错误行（关闭态已有内联提示，避免重复）。
     */
    fun afterSubmitRejected(closedFor: String?, requestedHost: String, code: Int): String? =
        if (code == ErrCode.REGISTER_CLOSED) afterResult(closedFor, requestedHost, enabled = false) else null
}
