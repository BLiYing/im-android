package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import com.libeyond.imandroid.sdk.protocol.ProtocolJson

/**
 * 本人资料的读写（对齐 iOS `IMHTTPService` 的 myProfile/updateProfile/updateUsername、
 * Web `imSdk.updateProfile`）。读自己用 [ContactApi.me]，写走这里。
 *
 * **昵称与用户名是两个东西、两个接口**：
 *  - 昵称（nickname）随便填，跟 avatar/phone/tags 一起走 `PUT /users/me`；
 *  - 用户名（username）是公开句柄，别人搜到我、也是登录名，规则严格，
 *    单独走 `POST /users/me/username`。
 */
class ProfileApi(private val http: HttpClient) {

    /**
     * 更新本人资料。**服务端是 PUT 整体替换**（`handleUpdateMyProfile`）——
     * 没传的字段等于清空，所以调用方必须把当前值一起传回来。
     * 这与会话设置的「整体替换三项」是同一个坑（MainScreen 的 `settings()` 注释）。
     */
    suspend fun update(
        nickname: String,
        avatarUrl: String,
        phone: String,
        tags: List<String>,
    ): UserCard {
        val body = buildJsonObject {
            put("nickname", nickname)
            put("avatar_url", avatarUrl)
            put("phone", phone)
            put("tags", tagsElement(tags))
        }
        return decode(http.call("PUT", "/api/v1/users/me", body), UserCard.serializer())
    }

    /**
     * 修改公开句柄。**只在用户真改了才调**——每次保存都发，会把「用户名已被占用」
     * 抛给一个压根没动用户名的人（iOS `saveUsernameIfChangedThenExitEditing` 的教训）。
     *
     * 服务端不吊销任何设备会话（JWT 带的是内部 ID + sid，与 username 无关），
     * 但**下次冷启动要用新名重登**，所以调用方必须把它写回本地会话。
     */
    suspend fun changeUsername(username: String): UserCard =
        decode(
            http.call("POST", "/api/v1/users/me/username", buildJsonObject { put("username", username) }),
            UserCard.serializer(),
        )

    private fun tagsElement(tags: List<String>): JsonElement =
        ProtocolJson.encodeToJsonElement(ListSerializer(String.serializer()), tags)
}
