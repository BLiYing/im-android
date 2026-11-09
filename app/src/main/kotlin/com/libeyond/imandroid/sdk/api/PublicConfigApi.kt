package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `GET /api/v1/public-config`（PROTOCOL §11，S4）：**免鉴权**，登录前就要知道的部署开关。
 * 目前只有 `register_enabled`（后台「系统设置 ▸ 安全风控 ▸ 开放注册」）。
 *
 * **拿不到一律当「开放」**：这只是提前提示，真正的拦截在 `POST /register`（回 `200007`）。
 * 反过来「失败当关闭」会让一台没部署这个接口的老服务端（404 / 非 JSON）把注册整个关掉。
 */
class PublicConfigApi(private val http: HttpClient) {

    /** 注册是否开放；网络失败 / 结构不符 / 字段缺失都回 true。 */
    suspend fun registerEnabled(): Boolean = try {
        registerEnabledOf(http.call("GET", PATH, authenticated = false))
    } catch (e: ApiException) {
        log.w("public_config_failed", "code" to e.code, "http" to e.httpStatus, "reqId" to e.requestId)
        true
    }

    companion object {
        private const val PATH = "/api/v1/public-config"
        private val log = IMLog.tag("IM.PublicConfig")

        /** 登录页用：host 是输入框里还没登录的地址，不带任何凭据。 */
        fun anonymous(host: String, useTls: Boolean) = PublicConfigApi(HttpClient(host, useTls))

        /** `data.register_enabled`；只有明确的 `false` 才算关闭，其余（缺失 / 非布尔 / data 为空）都算开放。 */
        fun registerEnabledOf(data: JsonElement?): Boolean =
            ((data as? JsonObject)?.get("register_enabled") as? JsonPrimitive)?.booleanOrNull != false
    }
}
