package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET /users/me/security`（PROTOCOL §7.3）。缺字段的默认值按「老服务端」取：有密码、没绑手机、短信不可用
 * ——这样确认身份弹层只剩密码页签，行为与改造前一致。
 */
@Serializable
data class SecurityStatus(
    @SerialName("phone_bound") val phoneBound: Boolean = false,
    /** 打码号码 `+86 138****8000`；未绑定时不带。 */
    @SerialName("phone_masked") val phoneMasked: String = "",
    @SerialName("password_set") val passwordSet: Boolean = true,
    @SerialName("sms_available") val smsAvailable: Boolean = false,
)

/** `POST /users/me/verify-code` 回包：发到本人已绑定号码。 */
@Serializable
data class VerifyCodeResult(
    @SerialName("expires_in") val expiresIn: Int = 300,
    @SerialName("resend_after") override val resendAfter: Int = DEFAULT_RESEND_SEC,
    @SerialName("phone_masked") val phoneMasked: String = "",
) : ResendHint

@Serializable
private data class PhoneBound(@SerialName("phone_masked") val phoneMasked: String = "")

/**
 * 「隐私与安全」里的登录手机号 / 身份确认接口（均需鉴权）。设 / 改密码走 [AuthApi.changePassword]（同一个接口）。
 * 请求体里有密码与验证码：这里**不记日志**。
 */
class SecurityApi(private val http: HttpClient) {

    suspend fun status(): SecurityStatus = decode(http.call("GET", "$BASE/security"), SecurityStatus.serializer())

    /** 给本人已绑定的号码发 `verify` 场景验证码（作 [Proof.SmsCode]）；没绑定回 `200040`。 */
    suspend fun verifyCode(lang: String): VerifyCodeResult =
        decode(http.call("POST", "$BASE/verify-code", buildJsonObject { put("lang", lang) }), VerifyCodeResult.serializer())

    /**
     * 绑定 / 更换登录手机号。[code] 是发到**新号码**的 `bind` 场景验证码；[proof] 证明是本人。
     * 两个验证码先校验、全部通过才用掉；出错时 `data.field` 标明是哪个（[PhoneAuthApi.errorField]）。
     * @return 新号码的打码形式。
     */
    suspend fun bindPhone(proof: Proof, countryCode: String, phone: String, code: String): String =
        decode(http.call("POST", "$BASE/phone", bindPhoneBody(proof, countryCode, phone, code)), PhoneBound.serializer()).phoneMasked

    companion object {
        private const val BASE = "/api/v1/users/me"

        internal fun bindPhoneBody(proof: Proof, countryCode: String, phone: String, code: String): JsonObject = buildJsonObject {
            put("proof", proof.toJson()); put("country_code", countryCode); put("phone", phone); put("code", code)
        }
    }
}
