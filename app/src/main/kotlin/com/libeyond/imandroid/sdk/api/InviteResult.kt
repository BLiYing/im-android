package com.libeyond.imandroid.sdk.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `POST /groups/{id}/members` 的响应（PROTOCOL §11 邀请条）。
 *
 * `added`=直接入群的；`pending`=普通成员邀请被「进群确认」拦下、转待审的。
 * 老服务端不回这两个字段（[known]=false），此时调用方按旧逻辑当「成功」处理，
 * 否则会把「没字段」误判成「都已在群里」。
 */
data class InviteResult(
    val added: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val known: Boolean = false,
) {
    companion object {
        fun parse(data: JsonElement?): InviteResult {
            val obj = data as? JsonObject ?: return InviteResult()
            if ("added" !in obj && "pending" !in obj) return InviteResult()
            return InviteResult(uids(obj["added"]), uids(obj["pending"]), known = true)
        }

        private fun uids(e: JsonElement?): List<String> =
            (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }
}
