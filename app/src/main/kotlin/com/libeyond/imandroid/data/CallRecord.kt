package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 通话记录消息（`content_type = "call"`）的**解析与渲染纯函数**。
 *
 * 设计与文案矩阵：`../IMServer/docs/design/CALL_RECORD_DESIGN.md` §3、UX 稿 §02。
 * **三端同一份纯函数 + 同一份向量**（`docs/conformance/call_record.json`，本端测试见 `CallRecordTest`）：
 * 改文案 = 先改向量，再三端各改各的。宿主不生成 SDK 的事实，只把 SDK 的 `callSummary` 折成这条消息。
 *
 * 判定顺序固定：① `d > 0` →「通话时长 mm:ss」（不看 reason）；② 否则按 `r` 查表；③ 表外 → `error`「通话未接通」。
 * 时长只用服务端给的值（不变量 I8），这里**绝不**自己相减。
 */
object CallRecord {

    /** 解析后的消息体。`reason` 已折叠：表外值 → `error`。 */
    data class Content(
        val callId: String,
        val video: Boolean,
        val reason: String,
        val durationSec: Int,
        val isGroup: Boolean,
    )

    enum class Tone { Normal, Missed }

    data class Rendered(
        val text: String,
        val tone: Tone,
        /** 单聊全部可点（点了按原类型回拨）；群系统条不可点。 */
        val tappable: Boolean,
        /** 会话列表预览（含 `[语音通话]` 前缀）。 */
        val preview: String,
    )

    private val KNOWN_REASONS = setOf(
        "hangup", "cancel", "reject", "no_answer", "busy", "offline",
        "network", "room_closed", "kicked", "error",
    )

    /** `d` 的上限（天）：与服务端 `86400*3` 截断一致，本地也夹一下，防止坏数据撑爆文案。 */
    private const val MAX_DURATION_SEC = 86400 * 3

    /** 会话列表预览尾部的「未接来电」——被叫侧且非红以外的结局都不含这个词，所以能当红字判据。 */
    private const val MISSED_TEXT = "未接来电"

    /** 缺 `cid` / `m` 非法 / 非 JSON → null（走「无法显示」降级）。 */
    fun parse(content: String): Content? {
        val o = runCatching { ProtocolJson.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return null
        val cid = o.str("cid")
        val m = o.str("m")
        if (cid.isEmpty() || (m != "audio" && m != "video")) return null
        val r = o.str("r").let { if (it in KNOWN_REASONS) it else "error" }
        val d = o.long("d").coerceIn(0L, MAX_DURATION_SEC.toLong()).toInt()
        return Content(cid, m == "video", r, d, isGroup = o.long("g") == 1L)
    }

    /**
     * @param viewerIsSender 看的人是不是这条消息的发送者（= 主叫；群里 = 发起人）
     * @param senderName 群系统条里的发起人名（备注 > 群昵称 > 昵称 > @句柄）；本人恒写「你」
     */
    fun render(c: Content, viewerIsSender: Boolean, senderName: String = ""): Rendered {
        val kind = if (c.video) "视频" else "语音"
        if (c.isGroup) {
            val who = if (viewerIsSender) "你" else senderName
            val (text, tail) = when {
                c.durationSec > 0 -> "${who}发起的群${kind}通话，时长 ${duration(c.durationSec)}" to "时长 ${duration(c.durationSec)}"
                c.reason == "no_answer" -> "${who}发起的群${kind}通话，无人接听" to "无人接听"
                c.reason == "cancel" -> "${who}取消了群${kind}通话" to "已取消"
                else -> "群通话已结束" to "已结束"
            }
            return Rendered(text, Tone.Normal, tappable = false, preview = "[群${kind}通话] $tail")
        }
        val (text, missed) = when {
            c.durationSec > 0 -> "通话时长 ${duration(c.durationSec)}" to false
            else -> when (c.reason) {
                "cancel" -> if (viewerIsSender) "已取消" to false else MISSED_TEXT to true
                "reject" -> if (viewerIsSender) "对方已拒绝" to false else "已拒绝" to false
                "no_answer" -> if (viewerIsSender) "对方无应答" to false else MISSED_TEXT to true
                "busy" -> if (viewerIsSender) "对方忙线" to false else MISSED_TEXT to true
                "offline" -> if (viewerIsSender) "对方不在线" to false else MISSED_TEXT to true
                else -> "通话未接通" to false
            }
        }
        return Rendered(text, if (missed) Tone.Missed else Tone.Normal, tappable = true, preview = "[${kind}通话] $text")
    }

    /** 解析 + 渲染；坏数据降级成一句人话，**绝不把 JSON 铺给用户**。 */
    fun renderRaw(content: String, viewerIsSender: Boolean, senderName: String = ""): Rendered =
        parse(content)?.let { render(it, viewerIsSender, senderName) }
            ?: Rendered("[音视频通话] 无法显示", Tone.Normal, tappable = false, preview = "[音视频通话]")

    /** 会话列表预览。`viewerIsSender` = 我是不是发送者（`row.sender == owner`）。 */
    fun preview(content: String, viewerIsSender: Boolean): String = renderRaw(content, viewerIsSender).preview

    /** 预览是否该整行标红（只有被叫侧的「未接来电」）。 */
    fun isMissedPreview(preview: String): Boolean = preview.endsWith(MISSED_TEXT)

    /** `<1h` → `mm:ss`；`≥1h` → `h:mm:ss`。 */
    fun duration(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    /**
     * 主叫在 SDK 的 `callSummary` 到达时发出的消息体。键序固定 `cid,m,r,d[,g]`。
     * 表外 reason 不在这里折叠（SDK 已折成 error）；服务端不校验枚举，原样存。
     */
    fun encode(callId: String, video: Boolean, reason: String, durationSec: Int, isGroup: Boolean): String =
        buildJsonObject {
            put("cid", callId)
            put("m", if (video) "video" else "audio")
            put("r", reason)
            put("d", durationSec.coerceAtLeast(0))
            if (isGroup) put("g", 1)
        }.toString()

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("").trim()

    private fun JsonObject.long(key: String): Long =
        runCatching { this[key]?.jsonPrimitive?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() } }
            .getOrNull() ?: 0L
}
