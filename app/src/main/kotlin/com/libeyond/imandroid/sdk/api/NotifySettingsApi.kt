package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.AccountNotifyFields
import com.libeyond.imandroid.data.BadgeSettings
import com.libeyond.imandroid.data.NotifSound
import com.libeyond.imandroid.data.NotifTypeSettings
import com.libeyond.imandroid.data.NotifySettingsResponse
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 账号级通知设置（M5）——`GET/PUT /api/v1/notify-settings`（`../../IMServer/docs/PROTOCOL.md` §6.13/§11）。
 * 私聊/群聊 `{enabled,preview,sound}` 与 `badge.include_muted` 三项挪到账号级、多端同步；应用内三项
 * （声音/振动/横幅）与桌面端音量仍是本机设置，不走这里（见 `AccountNotifySettingsStore` 类注释）。
 *
 * **同一套字段与迁移口径的另外两端**：iOS `IMNotificationSettings`、Web `notifySettings.ts`——
 * 三端各自按协议独立实现，行为必须一致。
 *
 * **解析失败一律回退「服务端还没这份设置」**（`exists=false`），让上层按迁移分支把本地值传上去，
 * 绝不抛——一次解析失败不该拦住整个通知设置页（同 `DownloadSettingsApi` 的纪律）。
 */
class NotifySettingsApi(private val http: HttpClient) {

    suspend fun get(): NotifySettingsResponse = parse(http.call("GET", PATH))

    /** 整体替换：body 顶层是 `{settings}`（PROTOCOL §11，与 download-settings 顶层直接是策略不同）。 */
    suspend fun put(fields: AccountNotifyFields): NotifySettingsResponse =
        parse(http.call("PUT", PATH, buildJsonObject { put("settings", NotifySettingsWire.encode(fields)) }))

    private fun parse(raw: JsonElement?): NotifySettingsResponse = runCatching {
        val o = raw as? JsonObject ?: return fallback("not_object")
        val version = o["version"]?.jsonPrimitive?.longOrNull ?: 0L
        val exists = o["exists"]?.jsonPrimitive?.booleanOrNull ?: false
        val fields = (o["settings"] as? JsonObject)?.let(NotifySettingsWire::decode) ?: AccountNotifyFields()
        NotifySettingsResponse(version, exists, fields)
    }.getOrElse { fallback(it.javaClass.simpleName) }

    private fun fallback(reason: String): NotifySettingsResponse {
        log.w("notify_settings_parse_failed", "error" to reason)
        return NotifySettingsResponse(0L, exists = false, fields = AccountNotifyFields())
    }

    internal companion object {
        private const val PATH = "/api/v1/notify-settings"
        private val log = IMLog.tag("IM.Notif")
    }
}

/**
 * `{private,group,badge}` ↔ JSON 的纯函数编解码（PROTOCOL §6.13）。**纯函数、不碰网络**，单测直接钉住：
 * - `badge.include_muted` 是 snake_case，别按 Kotlin 字段名 `includeMuted` 直接编码；
 * - `sound` 编成 wire 字符串（`none`/`default`/`chord`/`chime`/`rise`/`drop`），解码时未知值/缺失一律
 *   回落 `default`（[NotifSound.fromWire]），与本地 SharedPreferences 编解码（`NotificationSettingsCodec`）
 *   同一套枚举、同一条回落纪律——三端都不该出现"服务端吐了个本端不认识的 sound id 就崩"。
 */
object NotifySettingsWire {

    fun encode(f: AccountNotifyFields): JsonObject = buildJsonObject {
        put("private", encodeType(f.private))
        put("group", encodeType(f.group))
        putJsonObject("badge") { put("include_muted", f.badge.includeMuted) }
    }

    fun decode(o: JsonObject): AccountNotifyFields = AccountNotifyFields(
        private = decodeType(o["private"] as? JsonObject),
        group = decodeType(o["group"] as? JsonObject),
        badge = BadgeSettings(
            includeMuted = (o["badge"] as? JsonObject)?.get("include_muted")?.jsonPrimitive?.booleanOrNull ?: false,
        ),
    )

    private fun encodeType(t: NotifTypeSettings): JsonObject = buildJsonObject {
        put("enabled", t.enabled)
        put("preview", t.preview)
        put("sound", t.sound.wire)
    }

    /** 缺字段/坏值都各自回落默认——不是整块 `NotifTypeSettings()` 兜底，单个坏字段不拖累另外两个。 */
    private fun decodeType(o: JsonObject?): NotifTypeSettings = NotifTypeSettings(
        enabled = o?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: true,
        preview = o?.get("preview")?.jsonPrimitive?.booleanOrNull ?: true,
        sound = NotifSound.fromWire(o?.get("sound")?.jsonPrimitive?.contentOrNull),
    )
}
