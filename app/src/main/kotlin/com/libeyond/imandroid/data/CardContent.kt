package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 卡片类消息（个人名片 `contact` / 合并转发 `chat_record`）的**内容解析与摘要**。
 *
 * 为什么抽成纯函数：这两种消息的 `content` 是 JSON 字符串，解析分支多、且
 * **解析失败必须优雅降级而不是把裸 JSON 铺给用户看**——本端在做这一步之前正是那样，
 * 聊天页里赫然显示 `{"u":"7741990777","un":"user4996",...}`（2026-09-07 实体机实测）。
 *
 * 摘要口径逐条对齐 iOS `IMMediaUtil.m` 的 `IMRecordItemPreview` / `IMContactCardPreview`
 * 与 im-web 的同名实现——**三端摘要不一致时，同一条合并转发在三端显示不同文字**，
 * 而这种不一致没有任何自动化手段能发现。
 */
object CardContent {

    /** 名片卡片：`{u, un, n, a}`（协议 §4.3 contact）。字段都可能缺。 */
    data class Contact(
        val uid: String,
        val username: String = "",
        val nickname: String = "",
        val avatarUrl: String = "",
    ) {
        /** 卡片主标题：昵称优先，退到 uid。**收方本地有备注时由 UI 层覆盖**（备注不进内容）。 */
        val displayName: String get() = nickname.ifBlank { uid }

        /** 副标题恒为 `@句柄`；老消息没有 `un` → 留空。**绝不显示 uid**——那是内部 ID。 */
        val handle: String get() = if (username.isBlank()) "" else "@$username"
    }

    /** 合并转发卡片：`{t: 标题, items: [{n,ct,c,cap,fn,d,…}]}`。 */
    data class Record(val title: String, val lines: List<String>, val total: Int)

    /** 解析名片；非法 JSON 返回 null，由 UI 走「无法显示的名片」降级。 */
    fun parseContact(content: String): Contact? {
        val o = asObject(content) ?: return null
        val uid = o.str("u")
        if (uid.isBlank()) return null   // 没有 uid 的名片点不动，等同非法
        return Contact(uid = uid, username = o.str("un"), nickname = o.str("n"), avatarUrl = o.str("a"))
    }

    /** 名片在**别处**（会话列表预览、合并转发条目）的一行摘要。 */
    fun contactPreview(content: String): String {
        val c = parseContact(content) ?: return "[个人名片]"
        return "[个人名片] ${c.displayName}"
    }

    /**
     * 解析合并转发卡片，取标题 + 前 [maxLines] 条预览。
     *
     * @param maxLines 0 = 只要标题不要条目（嵌套卡片用，避免套娃展开）。
     */
    fun parseRecord(content: String, maxLines: Int = 3): Record? {
        val o = asObject(content) ?: return null
        val title = o.str("t").ifBlank { "聊天记录" }
        val items = (o["items"] as? kotlinx.serialization.json.JsonArray) ?: return Record(title, emptyList(), 0)
        val lines = if (maxLines <= 0) emptyList() else items.take(maxLines).mapNotNull { el ->
            val it = el as? JsonObject ?: return@mapNotNull null
            val name = it.str("n")
            val preview = itemPreview(it)
            if (name.isBlank()) preview else "$name: $preview"
        }
        return Record(title, lines, items.size)
    }

    /**
     * 合并转发里**一条**的预览文字。逐条对齐 iOS `IMRecordItemPreview`：
     * - 媒体/文件带 `cap`（图说）→ **有字显字**，超 60 截断加省略号
     * - image/video → `[图片]` / `[视频]`
     * - file → `[文件] 原名`（`fn` 随包带，收端不再只显 `[文件]`）
     * - voice/audio → `[语音] m:ss`（无 `d` 的老记录只显 `[语音]`）
     * - contact → 走 [contactPreview]
     * - chat_record（套娃）→ **只取子标题不展开**，`[聊天记录] 子标题`；
     *   子标题回落成「聊天记录」时不叠加，免得出现「[聊天记录] 聊天记录」
     */
    fun itemPreview(it: JsonObject): String {
        val ct = it.str("ct").ifBlank { ContentType.TEXT }
        val c = it.str("c")
        val cap = it.str("cap")
        if (cap.isNotBlank() && ct in setOf(ContentType.IMAGE, ContentType.VIDEO, ContentType.FILE)) {
            return if (cap.length > 60) cap.take(60) + "…" else cap
        }
        return when (ct) {
            ContentType.IMAGE -> "[图片]"
            ContentType.VIDEO -> "[视频]"
            ContentType.FILE -> {
                val fn = it.str("fn").ifBlank { c.substringAfterLast('/') }
                if (fn.isBlank()) "[文件]" else "[文件] $fn"
            }
            ContentType.CONTACT -> contactPreview(c)
            ContentType.VOICE, "audio" -> {
                val ms = (it["d"]?.jsonPrimitive?.longOrNull) ?: 0L
                if (ms <= 0) "[语音]" else "[语音] ${ms / 1000 / 60}:%02d".format(ms / 1000 % 60)
            }
            ContentType.CHAT_RECORD -> {
                val t = parseRecord(c, maxLines = 0)?.title.orEmpty()
                if (t.isNotBlank() && t != "聊天记录") "[聊天记录] $t" else "[聊天记录]"
            }
            else -> c
        }
    }

    // ——— 小工具 ———

    private fun asObject(s: String): JsonObject? =
        runCatching { ProtocolJson.parseToJsonElement(s) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("").trim()
}
