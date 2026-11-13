package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.session.DeviceIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** `sms/send-code` 的场景。`verify`（本人确认身份）走 [SecurityApi.verifyCode]，不在这里。 */
enum class SmsScene(val wire: String) { Login("login"), Reset("reset"), Bind("bind") }

/** 发码回包：验证码有效期与可重发间隔（秒）。 */
@Serializable
data class SmsSendResult(
    @SerialName("expires_in") val expiresIn: Int = 300,
    @SerialName("resend_after") override val resendAfter: Int = DEFAULT_RESEND_SEC,
) : ResendHint

/** `POST /login/sms` 的两种成功结果（另两种——两步验证 `200020`、冷静期 `200005`——以 [ApiException] 抛出，同密码登录）。 */
sealed interface SmsLoginResult {
    data class LoggedIn(val result: LoginResult) : SmsLoginResult

    /** 号码没注册过：带票去「完善资料」页（票 10 分钟、一次性）。 */
    data class NeedRegister(val ticket: String, val expiresIn: Int) : SmsLoginResult {
        override fun toString() = "NeedRegister(***, $expiresIn)"
    }
}

/**
 * 手机号与短信验证码（PROTOCOL §7.3）里**登录前**就要用的接口：发码、验证码登录、完善资料注册、
 * 忘记密码、凭 `restore_ticket` 恢复冷静期账号。只有 `scene=bind` 的发码需要登录态。
 *
 * 号码一律分开传 `country_code`（数字，不带 `+`）与 `phone`（本地号码）。
 * 请求体里有号码与验证码：这里**不记日志**，调用方日志只写事件名 + 业务码 + 打码号码。
 */
class PhoneAuthApi(private val http: HttpClient, private val device: DeviceIdentity) {

    suspend fun sendCode(scene: SmsScene, countryCode: String, phone: String, lang: String): SmsSendResult =
        decode(
            http.call("POST", "/api/v1/sms/send-code", sendCodeBody(scene, countryCode, phone, lang), authenticated = scene == SmsScene.Bind),
            SmsSendResult.serializer(),
        )

    suspend fun loginSms(countryCode: String, phone: String, code: String): SmsLoginResult {
        val body = buildJsonObject {
            put("country_code", countryCode); put("phone", phone); put("code", code)
            putDevice()
        }
        return loginSmsResultOf(http.call("POST", "/api/v1/login/sms", body, authenticated = false))
    }

    /** 完善资料：建号（无密码）并直接登录；响应与登录成功相同。 */
    suspend fun registerPhone(ticket: String, nickname: String): LoginResult {
        val body = buildJsonObject {
            put("register_ticket", ticket); put("nickname", nickname)
            putDevice()
        }
        return decode(http.call("POST", "/api/v1/register/phone", body, authenticated = false), LoginResult.serializer())
    }

    /** 忘记密码：[code] 是 `reset` 场景的验证码。成功后该账号所有设备下线。 */
    suspend fun resetPassword(countryCode: String, phone: String, code: String, newPassword: String) {
        http.call("POST", "/api/v1/password/reset", resetBody(countryCode, phone, code, newPassword), authenticated = false)
    }

    /** 验证码登录命中冷静期时 `200005` 带的一次性 `restore_ticket`（10 分钟）。不签 token。 */
    suspend fun restoreByTicket(ticket: String) {
        http.call("POST", "/api/v1/account/restore", buildJsonObject { put("restore_ticket", ticket) }, authenticated = false)
    }

    private fun JsonObjectBuilder.putDevice() {
        put("device_id", device.deviceId)
        put("platform", device.platform)
        put("device_name", device.deviceName)
        put("app_version", device.appVersion)
    }

    companion object {
        internal fun sendCodeBody(scene: SmsScene, countryCode: String, phone: String, lang: String): JsonObject = buildJsonObject {
            put("scene", scene.wire); put("country_code", countryCode); put("phone", phone); put("lang", lang)
        }

        internal fun resetBody(countryCode: String, phone: String, code: String, newPassword: String): JsonObject = buildJsonObject {
            put("country_code", countryCode); put("phone", phone); put("code", code); put("new_password", newPassword)
        }

        /** `need_register:true` → 去完善资料（票为空当结构不符）；其余按登录成功体解。 */
        fun loginSmsResultOf(data: JsonElement?): SmsLoginResult {
            val o = data as? JsonObject
            if ((o?.get("need_register") as? JsonPrimitive)?.booleanOrNull == true) {
                val ticket = (o["register_ticket"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (ticket.isEmpty()) throw ApiException(ApiException.TRANSPORT, "need_register 缺 register_ticket")
                return SmsLoginResult.NeedRegister(ticket, (o["expires_in"] as? JsonPrimitive)?.intOrNull ?: 0)
            }
            return SmsLoginResult.LoggedIn(decode(data, LoginResult.serializer()))
        }

        /** `200005` 的 `data.restore_ticket`（只有验证码登录才带）；没有返回 null，调用方走既有的密码恢复。 */
        fun restoreTicket(e: ApiException): String? = stringField(e.data, "restore_ticket")?.takeIf { it.isNotEmpty() }

        /** `POST /users/me/phone` 出错时 `data.field`：`"proof"` / `"code"`，其余接口没有。 */
        fun errorField(e: ApiException): String? = stringField(e.data, "field")

        /** 失败响应 `data.<key>` 的整数（`attempts_left` / `retry_after_sec`）；没有或不是整数返回 null。 */
        fun intField(e: ApiException, key: String): Int? = ((e.data as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

        private fun stringField(data: JsonElement?, key: String): String? =
            ((data as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
