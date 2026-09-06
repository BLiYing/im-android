package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.session.DeviceIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `POST /login` 与 `POST /token/refresh` 的返回。 */
@Serializable
data class LoginResult(
    val token: String,
    val uid: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
    /** **可选**：会话登记降级时服务端不下发，端上不能假定必然存在。 */
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Int = 0,
)

@Serializable
data class RegisterResult(
    @SerialName("user_id") val userId: String,
    val username: String,
)

/**
 * 鉴权接口（PROTOCOL §11）。
 *
 * 全部**免鉴权**（`authenticated = false`）——register/login 本就没有 token；
 * `token/refresh` 也刻意免鉴权，因为凭据本身即身份（旧 token 可能已经过期了，
 * 要求带一枚有效 token 才能续期是自相矛盾的）。
 */
class AuthApi(private val http: HttpClient, private val device: DeviceIdentity) {

    /**
     * 账号密码登录。**必带设备四件套**——缺 `device_id` 在生产环境直接 400。
     *
     * @param username 公开句柄（不是内部 ID）。`-dev-login` 下不传 password 即免密。
     */
    suspend fun login(username: String, password: String?): LoginResult {
        val body = buildJsonObject {
            put("username", username)
            if (!password.isNullOrEmpty()) put("password", password)
            put("device_id", device.deviceId)
            put("platform", device.platform)
            put("device_name", device.deviceName)
            put("app_version", device.appVersion)
        }
        return decode(http.call("POST", "/api/v1/login", body, authenticated = false), LoginResult.serializer())
    }

    /** 注册。`username` 须匹配 `^[a-z0-9_]{5,32}$`；`nickname` 必填 ≤32 runes。 */
    suspend fun register(username: String, password: String, nickname: String): RegisterResult {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("nickname", nickname)
        }
        return decode(http.call("POST", "/api/v1/register", body, authenticated = false), RegisterResult.serializer())
    }

    /**
     * 用长效凭据换新的访问 token，**落在同一台设备会话 sid 上**。
     *
     * 服务端**不轮换** refresh_token（轮换能多一层"被盗即察觉"，但客户端并发续期时
     * 必有一方被误踢下线）。唯一轮换点是改密码。
     *
     * 失效一律回 `100101`，不区分"不存在"与"已吊销"。
     */
    suspend fun refresh(refreshToken: String): LoginResult {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        return decode(http.call("POST", "/api/v1/token/refresh", body, authenticated = false), LoginResult.serializer())
    }

    /**
     * 退出登录——**必须调**。
     *
     * 只清本地凭据的话服务端会话仍然有效：那枚绑定它的 `refresh_token` 最长 180 天内
     * 还能换新 token，且该设备一直留在「已登录设备」列表里。
     *
     * 重复退出会被吊销闸拦成 `100101`，端上一律当"已退出"处理。
     */
    suspend fun logout() {
        http.call("POST", "/api/v1/logout")
    }
}
