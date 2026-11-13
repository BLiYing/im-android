package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 开放短信的地区（`sms_regions` 一项）：`code` = 国家码数字（不带 `+`），`iso` = ISO 3166 二字母。 */
data class SmsRegion(val code: String, val iso: String)

/**
 * `GET /api/v1/public-config` 里登录页要用的部署开关（S4 + PROTOCOL §7.3）。
 *
 * 字段缺失按「老服务端」解释：短信不可用（没有验证码页签、不显示忘记密码）、可用户名注册。
 */
data class PublicConfig(
    val registerEnabled: Boolean = true,
    val smsAvailable: Boolean = false,
    /** 第一个是默认地区；短信不可用时为空。 */
    val smsRegions: List<SmsRegion> = emptyList(),
    /** 此刻真正能用的注册方式（`"username"` / `"phone"` 的子集，服务端已叠加开放注册与短信可用性）。 */
    val registerMethods: List<String> = listOf(METHOD_USERNAME),
) {
    companion object {
        const val METHOD_USERNAME = "username"
        const val METHOD_PHONE = "phone"
    }
}

/**
 * `GET /api/v1/public-config`（PROTOCOL §11，S4）：**免鉴权**，登录前就要知道的部署开关。
 *
 * **拿不到一律当「开放」**：这只是提前提示，真正的拦截在 `POST /register`（回 `200007`）。
 * 反过来「失败当关闭」会让一台没部署这个接口的老服务端（404 / 非 JSON）把注册整个关掉。
 */
class PublicConfigApi(private val http: HttpClient) {

    /** 注册是否开放；网络失败 / 结构不符 / 字段缺失都回 true。 */
    suspend fun registerEnabled(): Boolean = fetch()?.registerEnabled ?: true

    /** 整份配置；网络 / 服务端失败回 null（调用方按老服务端口径处理，或保留上一次的值）。 */
    suspend fun fetch(): PublicConfig? = try {
        configOf(http.call("GET", PATH, authenticated = false))
    } catch (e: ApiException) {
        log.w("public_config_failed", "code" to e.code, "http" to e.httpStatus, "reqId" to e.requestId)
        null
    }

    companion object {
        private const val PATH = "/api/v1/public-config"
        private val log = IMLog.tag("IM.PublicConfig")

        /** 登录页用：host 是输入框里还没登录的地址，不带任何凭据。 */
        fun anonymous(host: String, useTls: Boolean) = PublicConfigApi(HttpClient(host, useTls))

        /** `data.register_enabled`；只有明确的 `false` 才算关闭，其余（缺失 / 非布尔 / data 为空）都算开放。 */
        fun registerEnabledOf(data: JsonElement?): Boolean =
            ((data as? JsonObject)?.get("register_enabled") as? JsonPrimitive)?.booleanOrNull != false

        /** 解析整份配置；缺字段按 [PublicConfig] 的默认值（老服务端口径）。 */
        fun configOf(data: JsonElement?): PublicConfig {
            val o = data as? JsonObject
            val sms = (o?.get("sms_available") as? JsonPrimitive)?.booleanOrNull == true
            val regions = (o?.get("sms_regions") as? JsonArray).orEmpty().mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val code = (r["code"] as? JsonPrimitive)?.contentOrNull?.filter(Char::isDigit).orEmpty()
                if (code.isEmpty()) null else SmsRegion(code, (r["iso"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            val methods = (o?.get("register_methods") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: listOf(PublicConfig.METHOD_USERNAME)
            return PublicConfig(
                registerEnabled = registerEnabledOf(data),
                smsAvailable = sms,
                // 短信可用却没下发地区（不该出现）：按中国大陆兜底，别让验证码页签没法用
                smsRegions = if (sms && regions.isEmpty()) listOf(SmsRegion("86", "CN")) else regions,
                registerMethods = methods,
            )
        }
    }
}
