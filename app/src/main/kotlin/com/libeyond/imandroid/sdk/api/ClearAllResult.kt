package com.libeyond.imandroid.sdk.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 被清会话及其位点（`POST /users/me/conversations/clear` 的 `items` 一项，PROTOCOL §6.8）。 */
data class ClearedConv(val convId: String, val clearedAt: Long)

/** [cleared] 以服务端为准（toast 用）；[items] 供发起端立即清本机（会话列表快照已不再返回被隐藏的会话，位点只能由此取得）。 */
data class ClearAllResult(val cleared: Int, val items: List<ClearedConv>) {
    companion object {
        /** 容错解析：缺 `cleared` 时取 items 个数；items 里缺 conv_id 的项丢弃，缺 cleared_at 的位点记 0（本机退回 head 口径）。 */
        fun parse(data: JsonElement?): ClearAllResult {
            val obj = data as? JsonObject ?: return ClearAllResult(0, emptyList())
            val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["conv_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isBlank()) null else ClearedConv(id, (o["cleared_at"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }
            val n = (obj["cleared"] as? JsonPrimitive)?.intOrNull ?: items.size
            return ClearAllResult(n, items)
        }
    }
}
