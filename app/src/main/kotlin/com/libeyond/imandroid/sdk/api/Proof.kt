package com.libeyond.imandroid.sdk.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 敏感操作的身份证明（PROTOCOL §7.3 `proof`）：密码或发到本人已绑定号码的短信验证码，二选一。
 *
 * 用到它的接口：改 / 设密码、注销账号、导出数据、邮箱 send-code / unbind、两步验证 setup / disable /
 * recovery-codes、绑定 / 更换手机号。请求体里只传 `proof`，不再传旧的顶层 `password` / `old_password`。
 *
 * [toString] 打码：这两个值都不能进日志。
 *
 * [None] = 空证明（PHONE_AUTH_DESIGN §15.2「最近验证过免确认」）：请求体里**既不带 `proof` 也不带旧的顶层 `password`**，
 * 由服务端按本会话最近一次验证身份的时刻放行，否则回 `200049`。请求体一律经 [putProof] 写。
 */
sealed interface Proof {
    /** `proof` 字段的值；[None] = null（不写这个字段）。 */
    fun toJson(): JsonObject?

    data object None : Proof {
        override fun toJson(): JsonObject? = null
    }

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

/** 写 `proof` 字段；[Proof.None] 什么都不写（服务端只认「没有 proof」为空证明）。 */
fun JsonObjectBuilder.putProof(proof: Proof) {
    proof.toJson()?.let { put("proof", it) }
}
