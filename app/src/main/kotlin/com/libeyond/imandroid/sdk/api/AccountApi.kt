package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 阻断注销的群（`owned_groups` 一项，PROTOCOL §6.16.1）。 */
data class OwnedGroup(val convId: String, val name: String)

/**
 * 注销账号 / 冷静期恢复（PROTOCOL §6.16.1）：
 * `POST /account/delete {password}`（需鉴权）、`POST /account/restore {username,password}`（免鉴权，等同登录）。
 */
class AccountApi(private val http: HttpClient) {

    /** 校验密码后进入 7 天冷静期并吊销本账号全部会话。群主会被拒（100103 + `owned_groups`）。 */
    suspend fun delete(password: String) {
        http.call("POST", "/api/v1/account/delete", buildJsonObject { put("password", password) })
    }

    /** 冷静期内凭原密码恢复。免鉴权，服务端须套登录同款限流。 */
    suspend fun restore(username: String, password: String) {
        http.call(
            "POST", "/api/v1/account/restore",
            buildJsonObject { put("username", username); put("password", password) },
            authenticated = false,
        )
    }

    companion object {
        /** 从 100103 失败响应的 `data.owned_groups:[{conv_id,name}]` 取阻断注销的群；conv_id 与 name 都缺的才丢弃（无名群 name 可为空，展示时回退「未命名群聊」；缺 conv_id 的不可点）。同 im-web `accountError`。 */
        fun ownedGroups(data: JsonElement?): List<OwnedGroup> {
            val arr = (data as? JsonObject)?.get("owned_groups") as? JsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                OwnedGroup(str("conv_id"), str("name")).takeIf { it.convId.isNotBlank() || it.name.isNotBlank() }
            }
        }

        /** `delete_at`（PROTOCOL 定为毫秒）。保留「小于 1e12 视为秒」的兜底；缺失/非数字为 null。 */
        fun deleteAtMillis(data: JsonElement?): Long? {
            val v = ((data as? JsonObject)?.get("delete_at") as? JsonPrimitive)?.longOrNull ?: return null
            return if (v < 1_000_000_000_000L) v * 1000 else v
        }
    }
}
