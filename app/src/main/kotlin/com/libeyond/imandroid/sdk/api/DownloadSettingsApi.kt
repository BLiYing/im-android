package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.data.DownloadSettings
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
private data class SettingsResp(
    val version: Long = 0,
    val settings: DownloadSettings = DownloadSettings(),
)

/**
 * 账号级自动下载策略（M4-7）——`GET/PUT /api/v1/download-settings` + `POST …/reset`。
 *
 * 服务端 bump 版本并推 `capabilities_update`，其它端据此重拉（多端同步）。
 *
 * **解析失败一律回退出厂默认，绝不抛**：策略拉不到不该让整个聊天页崩，
 * 最差的后果只是这一次按默认策略下载（与 Web `parseDownloadSettings` 同一纪律）。
 */
class DownloadSettingsApi(private val http: HttpClient) {

    suspend fun get(): Pair<Long, DownloadSettings> = parse(http.call("GET", PATH))

    /** 整体替换（与群设置同：服务端存的是整份 JSON，改一项也要把整份传回）。body 形状见 [putBody]。 */
    suspend fun put(s: DownloadSettings): Pair<Long, DownloadSettings> =
        parse(http.call("PUT", PATH, putBody(s)))

    suspend fun reset(): Pair<Long, DownloadSettings> = parse(http.call("POST", "$PATH/reset"))

    private fun parse(raw: kotlinx.serialization.json.JsonElement?): Pair<Long, DownloadSettings> =
        runCatching {
            val o = raw as? JsonObject ?: return 0L to DownloadPolicy.defaults()
            val r = ProtocolJson.decodeFromJsonElement(SettingsResp.serializer(), o)
            r.version to r.settings
        }.getOrElse { 0L to DownloadPolicy.defaults() }

    internal companion object {
        private const val PATH = "/api/v1/download-settings"

        /**
         * 整份替换专用：**默认值也上线**。[ProtocolJson] 的 `encodeDefaults = false` 对增量帧是对的，
         * 对这里是错的——Kotlin 默认 `enabled/single/group = true`，省略后 Go 解码成 false，
         * 一次保存就把整套策略静默清成全关（服务端只探测两个网络在不在，探不到字段级缺失）。
         */
        private val FullJson = Json(from = ProtocolJson) { encodeDefaults = true }

        /** body **顶层就是** `{cellular, wifi}`（iOS / Web 同），不套 `settings`——套了服务端回 400。 */
        fun putBody(s: DownloadSettings): JsonObject =
            FullJson.encodeToJsonElement(DownloadSettings.serializer(), s).jsonObject
    }
}
