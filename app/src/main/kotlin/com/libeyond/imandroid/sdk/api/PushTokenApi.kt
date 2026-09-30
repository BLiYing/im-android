package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设备推送令牌上报（M5 批次 2，`../../IMServer/docs/design/PUSH_M5_DESIGN.md` §1.1；协议形状由父任务
 * 简报确定，与服务端 `internal/push` 并行扩展的多 provider 支持对齐，**接口不会变**）：
 * `PUT/DELETE /api/v1/push/token`，与 iOS APNs 共用同一张服务端表（`provider` 区分）。
 *
 * **只此一处直接拼这两个接口的请求体**，别处不需要知道字段名字面量——同 [NotifySettingsApi]/
 * [DownloadSettingsApi] 的分工口径。
 */
class PushTokenApi(private val http: HttpClient) {

    /**
     * 上报（或续报）本机 FCM 令牌。
     *
     * `environment` 固定传 `"production"`——那是 APNs 沙盒/生产环境的概念，FCM 场景没有对应语义
     * 区分，服务端也不据此做分流（父任务简报明确约定）。`bundle_id` 用 [BuildConfig.APPLICATION_ID]
     * （AGP 恒生成，值即 `com.libeyond.imandroid`），不硬编码字符串——包名一旦改（不太可能但万一），
     * 这里跟着编译期常量走，不会漏改。
     */
    suspend fun put(token: String, locale: String) {
        http.call(
            "PUT",
            PATH,
            buildJsonObject {
                put("provider", PROVIDER_FCM)
                put("token", token)
                put("environment", "production")
                put("bundle_id", BuildConfig.APPLICATION_ID)
                put("locale", locale)
            },
        )
    }

    /** 用户在设置里关掉「接收离线推送」时调用——幂等，服务端按当前会话（sid）删对应令牌行。 */
    suspend fun delete() {
        http.call("DELETE", PATH)
    }

    companion object {
        const val PROVIDER_FCM = "fcm"
        private const val PATH = "/api/v1/push/token"
    }
}
