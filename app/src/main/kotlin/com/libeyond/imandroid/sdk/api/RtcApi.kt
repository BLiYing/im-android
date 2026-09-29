package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

/** `POST /api/v1/rtc/token` 的响应（对齐 IMServer `rtcTokenData`，见 IMServer `docs/PROTOCOL.md`）。 */
@Serializable
data class RtcTokenResult(
    val token: String = "",
    @SerialName("expires_at_ms") val expiresAtMs: Long = 0,
    @SerialName("expires_in_sec") val expiresInSec: Long = 0,
)

/**
 * im-rtc（音视频通话）接入票：由 IMServer 代为向 im-rtc-server 换票，本端不需要也不该知道
 * 任何签名密钥。`HttpClient.call` 默认 `authenticated=true`，会自动带上当前会话的 Bearer
 * token——不需要像换票请求本身那样手动传 token（对齐项目里其它业务接口的写法）。
 *
 * 未配置 / 换票失败经 [com.libeyond.imandroid.sdk.http.ApiException] 抛出（业务码见 IMServer
 * errcode `600001`/`600002`），调用方（`RtcCall`）按"通话入口不可用"静默降级。
 */
class RtcApi(private val http: HttpClient) {
    suspend fun fetchToken(): RtcTokenResult =
        decode(http.call("POST", "/api/v1/rtc/token", buildJsonObject {}), RtcTokenResult.serializer())
}
