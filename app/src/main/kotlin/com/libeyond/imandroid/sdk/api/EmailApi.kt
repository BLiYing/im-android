package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 邮箱登录（PROTOCOL §7.2）。`GET /users/me/email` 的回包：`available=false` = 服务端没配 SMTP
 * （已绑定的账号仍可进已绑定页看邮箱并解绑）。邮箱只有本人可见。
 */
@Serializable
data class EmailStatus(
    val available: Boolean = true,
    val bound: Boolean = false,
    val email: String = "",
    /** 毫秒时间戳；未绑定时不带。 */
    @SerialName("verified_at") val verifiedAt: Long = 0,
)

/** `send-code` 回包：验证码有效期与可重发间隔（秒）。 */
@Serializable
data class EmailSendResult(
    @SerialName("expires_in") val expiresIn: Int = 600,
    @SerialName("resend_after") override val resendAfter: Int = DEFAULT_RESEND_SEC,
) : ResendHint

/** `verify` 成功回包。 */
@Serializable
data class EmailBound(
    val email: String = "",
    @SerialName("verified_at") val verifiedAt: Long = 0,
)

/**
 * `/users/me/email` 下的各接口（均需鉴权）。
 * 请求体里有密码与验证码：这里**不记日志**，调用方日志也只写事件名 + 业务码。
 */
class EmailApi(private val http: HttpClient) {

    suspend fun status(): EmailStatus = decode(http.call("GET", BASE), EmailStatus.serializer())

    /** 验身份（密码或短信验证码）后发验证码（绑定 / 更换共用）。[lang]：`en` / `zh-Hans`，决定邮件语言。 */
    suspend fun sendCode(email: String, proof: Proof, lang: String): EmailSendResult =
        decode(http.call("POST", "$BASE/send-code", sendBody(email, proof, lang)), EmailSendResult.serializer())

    suspend fun verify(email: String, code: String, lang: String): EmailBound =
        decode(http.call("POST", "$BASE/verify", verifyBody(email, code, lang)), EmailBound.serializer())

    /** 解绑只要身份证明，不要邮箱验证码（邮箱可能已收不到）。 */
    suspend fun unbind(proof: Proof) {
        http.call("POST", "$BASE/unbind", unbindBody(proof))
    }

    companion object {
        private const val BASE = "/api/v1/users/me/email"

        internal fun sendBody(email: String, proof: Proof, lang: String): JsonObject = buildJsonObject {
            put("email", email); put("proof", proof.toJson()); put("lang", lang)
        }
        internal fun verifyBody(email: String, code: String, lang: String): JsonObject = buildJsonObject {
            put("email", email); put("code", code); put("lang", lang)
        }
        internal fun unbindBody(proof: Proof): JsonObject = buildJsonObject { put("proof", proof.toJson()) }

        /** 失败响应 `data.<key>` 的整数（`attempts_left` / `retry_after_sec`）；没有或不是整数返回 null。 */
        fun intField(e: ApiException, key: String): Int? = try {
            e.data?.jsonObject?.get(key)?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }
    }
}
