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

/**
 * 注销账号（DELETE_ACCOUNT_DESIGN §5）。
 *
 * **契约待与后端 `PROTOCOL.md` 对账**：当前按设计稿 §5 写——
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
        /**
         * 从失败响应的 `data` 里取阻断注销的群名。宽松解析：`owned_groups` 元素是字符串，
         * 或带 `name`/`group_name` 的对象都认（契约未定稿，别因形状差异让阻断态整页消失）。
         */
        fun ownedGroups(data: JsonElement?): List<String> {
            val arr = (data as? JsonObject)?.get("owned_groups") as? JsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                when (e) {
                    is JsonPrimitive -> e.contentOrNull
                    is JsonObject -> (e["name"] ?: e["group_name"])?.let { (it as? JsonPrimitive)?.contentOrNull }
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }
        }

        /** `delete_at` → 毫秒。秒/毫秒两种口径都认（小于 1e12 视为秒）；缺失/非数字为 null。 */
        fun deleteAtMillis(data: JsonElement?): Long? {
            val v = ((data as? JsonObject)?.get("delete_at") as? JsonPrimitive)?.longOrNull ?: return null
            return if (v < 1_000_000_000_000L) v * 1000 else v
        }
    }
}
