package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.FolderLimits
import com.libeyond.imandroid.data.FolderSnapshot
import com.libeyond.imandroid.data.toSnapshot
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.protocol.FolderUpdateData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 会话文件夹的远端能力（便于 [com.libeyond.imandroid.data.ChatFolderStore] 在 JVM 单测里注入假实现）。 */
interface ChatFoldersRemote {
    suspend fun list(): FolderSnapshot
    suspend fun create(name: String, convIds: List<String>, clientId: String): FolderSnapshot
    suspend fun rename(folderId: String, name: String): FolderSnapshot
    suspend fun delete(folderId: String): FolderSnapshot
    suspend fun reorder(folderIds: List<String>): FolderSnapshot
    suspend fun changeConvs(folderId: String, add: List<String>, remove: List<String>): FolderSnapshot
}

/**
 * 会话文件夹 REST（`../../IMServer/docs/PROTOCOL.md` §6.15 / §11）。所有接口成功后回的都是整表
 * `{version, folders[], limits}`（与 folder_update 帧同形），由 `ChatFolderStore` 整表替换。
 *
 * **解析失败一律抛出**（不静默回空表）：空表会被当成「用户没有文件夹」整表替换掉本地缓存，比报错糟得多。
 */
class ChatFoldersApi(private val http: HttpClient) : ChatFoldersRemote {

    override suspend fun list(): FolderSnapshot = parse(http.call("GET", PATH))

    override suspend fun create(name: String, convIds: List<String>, clientId: String): FolderSnapshot =
        parse(
            http.call(
                "POST", PATH,
                buildJsonObject {
                    put("name", name)
                    put("conv_ids", strings(convIds))
                    // 网络重发时服务端据此回既存结果而非报重名（PROTOCOL §11）
                    if (clientId.isNotEmpty()) put("client_id", clientId)
                },
            ),
        )

    override suspend fun rename(folderId: String, name: String): FolderSnapshot =
        parse(http.call("PATCH", "$PATH/$folderId", buildJsonObject { put("name", name) }))

    override suspend fun delete(folderId: String): FolderSnapshot = parse(http.call("DELETE", "$PATH/$folderId"))

    override suspend fun reorder(folderIds: List<String>): FolderSnapshot =
        parse(http.call("PUT", "$PATH/order", buildJsonObject { put("ids", strings(folderIds)) }))

    override suspend fun changeConvs(folderId: String, add: List<String>, remove: List<String>): FolderSnapshot =
        parse(
            http.call(
                "POST", "$PATH/$folderId/convs",
                buildJsonObject {
                    put("add", strings(add))
                    put("remove", strings(remove))
                },
            ),
        )

    internal companion object {
        private const val PATH = "/api/v1/folders"

        private fun strings(list: List<String>) = JsonArray(list.map { JsonPrimitive(it) })

        /** `{version, folders[], limits?}` → 快照。纯函数，单测直接钉。 */
        fun parse(raw: JsonElement?): FolderSnapshot {
            val o = raw as? JsonObject ?: error("folders: response is not an object")
            val data = ProtocolJson.decodeFromJsonElement(FolderUpdateData.serializer(), o)
            val lim = o["limits"] as? JsonObject
            fun int(key: String, default: Int) = lim?.get(key)?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: default
            val d = FolderLimits()
            return data.toSnapshot(FolderLimits(int("max_folders", d.maxFolders), int("max_convs", d.maxConvs), int("max_name", d.maxName)))
        }
    }
}
