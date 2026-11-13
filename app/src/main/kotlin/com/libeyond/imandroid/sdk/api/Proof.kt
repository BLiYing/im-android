package com.libeyond.imandroid.sdk.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 敏感操作的身份证明（PROTOCOL §7.3 `proof`）：密码或发到本人已绑定号码的短信验证码，二选一。
 *
 * 用到它的接口：改 / 设密码、注销账号、导出数据、邮箱 send-code / unbind、两步验证 setup / disable /
 * recovery-codes、绑定 / 更换手机号。请求体里只传 `proof`，不再传旧的顶层 `password` / `old_password`。
 *
 * [toString] 打码：这两个值都不能进日志。
 */
sealed interface Proof {
    fun toJson(): JsonObject

    data class Password(val password: String) : Proof {
        override fun toJson(): JsonObject = buildJsonObject { put("password", password) }
        override fun toString() = "Proof.Password(***)"
    }

    /** 验证码来自 `POST /users/me/verify-code`，一个码只能完成一次操作。 */
    data class SmsCode(val code: String) : Proof {
        override fun toJson(): JsonObject = buildJsonObject { put("sms_code", code) }
        override fun toString() = "Proof.SmsCode(***)"
    }
}
