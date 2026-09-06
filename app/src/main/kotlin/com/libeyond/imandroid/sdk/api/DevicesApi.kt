package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceSession(
    @SerialName("session_id") val sessionId: String = "",
    val platform: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("login_ip") val loginIp: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_active_at") val lastActiveAt: Long = 0,
    val online: Boolean = false,
    /** 是否本机。**列表里 current 全为 false 时别把某一行当成本机**——
     *  iOS 曾因此把别人的设备显示成"当前设备"，误踢风险。 */
    val current: Boolean = false,
)

@Serializable
private data class DevicesResp(val devices: List<DeviceSession> = emptyList())

/** 已登录设备（多设备管理 P2）。也是 [com.libeyond.imandroid.sdk.session.TokenSession] 的探活接口。 */
class DevicesApi(private val http: HttpClient) {

    suspend fun list(): List<DeviceSession> =
        decode(http.call("GET", "/api/v1/devices"), DevicesResp.serializer()).devices

    /**
     * 探活：只关心「这枚 token 还认不认」，不关心返回内容。
     * 选 `/devices` 而不是别的接口，是与 Web 保持同一个探针，便于两端对着同一条服务端日志排查。
     */
    suspend fun probe() {
        http.call("GET", "/api/v1/devices")
    }

    suspend fun revoke(sessionId: String) {
        http.call("POST", "/api/v1/devices/$sessionId/revoke")
    }

    suspend fun revokeOthers() {
        http.call("POST", "/api/v1/devices/revoke-others")
    }
}
