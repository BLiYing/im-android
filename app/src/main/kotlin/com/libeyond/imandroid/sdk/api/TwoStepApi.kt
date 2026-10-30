package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 两步验证（PROTOCOL §7.1）。
 *
 * **所有携带密钥 / 恢复码 / ticket 的类型都重写 `toString` 打码**：data class 默认的 `toString`
 * 会把每个字段拼出来，一行 `log.d("x" to result)` 就把机密写进日志（日志红线）。
 */
@Serializable
data class TwoStepStatus(
    val enabled: Boolean = false,
    /** 毫秒时间戳；未开启时不带。 */
    @SerialName("enabled_at") val enabledAt: Long = 0,
    @SerialName("recovery_remaining") val recoveryRemaining: Int = 0,
)

@Serializable
data class TwoStepSetup(
    /** 无分隔的 Base32；界面按 4 位一组展示。 */
    val secret: String,
    @SerialName("otpauth_uri") val otpauthUri: String,
    @SerialName("expires_in") val expiresIn: Int = 1800,
) {
    override fun toString() = "TwoStepSetup(***)"
}

@Serializable
data class RecoveryCodes(@SerialName("recovery_codes") val recoveryCodes: List<String>) {
    override fun toString() = "RecoveryCodes(n=${recoveryCodes.size})"
}

/** 登录第一步回的 `200020` 的 `data`。ticket 只留内存，离开二步页即丢弃。 */
@Serializable
data class TwoStepChallenge(
    val ticket: String,
    val methods: List<String> = emptyList(),
    @SerialName("expires_in") val expiresIn: Int = 0,
) {
    override fun toString() = "TwoStepChallenge(***)"

    companion object {
        /** 从 `POST /login` 的失败里取挑战；不是 `200020` 或 `data` 不合格返回 null。 */
        fun from(e: ApiException): TwoStepChallenge? {
            if (e.code != ErrCode.TWO_STEP_REQUIRED) return null
            val d = e.data ?: return null
            return try {
                ProtocolJson.decodeFromJsonElement(serializer(), d).takeIf { it.ticket.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        /** `200021` 在登录第二步带的 `data.attempts_left`；没有返回 null。 */
        fun attemptsLeft(e: ApiException): Int? = try {
            e.data?.jsonObject?.get("attempts_left")?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }
    }
}

/** `/users/me/two-step` 下的各接口（均需鉴权）。 */
class TwoStepApi(private val http: HttpClient) {

    suspend fun status(): TwoStepStatus = decode(http.call("GET", BASE), TwoStepStatus.serializer())

    /** 验密码后生成待确认密钥；已开启 `200024`，服务端无密钥 `200025`。再次调用会换新密钥。 */
    suspend fun setup(password: String): TwoStepSetup =
        decode(http.call("POST", "$BASE/setup", setupBody(password)), TwoStepSetup.serializer())

    /** 用动态码确认；成功即已启用，恢复码仅此一次明文。 */
    suspend fun enable(code: String): RecoveryCodes =
        decode(http.call("POST", "$BASE/enable", enableBody(code)), RecoveryCodes.serializer())

    /** [code] 为 6 位动态码或恢复码。 */
    suspend fun disable(password: String, code: String) {
        http.call("POST", "$BASE/disable", verifyBody(password, code))
    }

    /** 重新生成恢复码（旧码全部作废）。[code] 同 [disable]。 */
    suspend fun regenerate(password: String, code: String): RecoveryCodes =
        decode(http.call("POST", "$BASE/recovery-codes", verifyBody(password, code)), RecoveryCodes.serializer())

    companion object {
        private const val BASE = "/api/v1/users/me/two-step"

        internal fun setupBody(password: String): JsonObject = buildJsonObject { put("password", password) }
        internal fun enableBody(code: String): JsonObject = buildJsonObject { put("code", code) }
        internal fun verifyBody(password: String, code: String): JsonObject = buildJsonObject {
            put("password", password)
            put("code", code)
        }
        internal fun loginBody(ticket: String, code: String): JsonObject = buildJsonObject {
            put("ticket", JsonPrimitive(ticket))
            put("code", code)
        }
    }
}
