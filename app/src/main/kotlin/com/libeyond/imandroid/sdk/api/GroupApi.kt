package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** 群成员（对齐 `internal/group.MemberView`）。 */
@Serializable
data class GroupMember(
    /** 内部 ID。**客户端不得展示。** */
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val nickname: String = "",
    /** 我在本群的昵称；空=未设置，回退全局昵称。 */
    @SerialName("group_nickname") val groupNickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    /** owner | admin | member */
    val role: String = "",
    @SerialName("joined_at") val joinedAt: Long = 0,
    /** 成员级禁言到期；0=未禁言。 */
    @SerialName("mute_until") val muteUntil: Long = 0,
) {
    /** 群内显示名：**群昵称 → 全局昵称 → @句柄 → 未命名**（末级不是 uid）。 */
    val displayName: String
        get() = groupNickname.ifBlank { nickname }
            .ifBlank { if (username.isBlank()) "未命名用户" else "@$username" }

    val handle: String get() = if (username.isBlank()) "" else "@$username"
    val isOwner: Boolean get() = role == ROLE_OWNER
    val isAdmin: Boolean get() = role == ROLE_ADMIN
    val isManager: Boolean get() = isOwner || isAdmin

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_ADMIN = "admin"
        const val ROLE_MEMBER = "member"
    }
}

/** 群资料（对齐 `internal/group.Info`）。 */
@Serializable
data class GroupInfo(
    /** 与消息层 conv_id 同名，进群聊即用此值。 */
    @SerialName("conv_id") val convId: String = "",
    val name: String = "",
    val owner: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("my_role") val myRole: String = "",
    /**
     * 成员列表。
     * **⚠️ 字段名陷阱**：这里是 `members`，而 `GET /groups/{id}/members` 的是 `items`——
     * 两个接口不同名。照直觉写成同一个会静默拿到空列表：
     * 2026-08-31 三端有两端同时踩中，Web 端把 undefined 塞进 state 后 .map 直接白屏。
     */
    val members: List<GroupMember> = emptyList(),
    val intro: String = "",
    val announcement: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("my_nickname") val myNickname: String = "",
    @SerialName("mute_until") val muteUntil: Long = 0,
    @SerialName("my_mute_until") val myMuteUntil: Long = 0,
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("perm_invite") val permInvite: Boolean = false,
    @SerialName("history_visible") val historyVisible: Boolean = false,
    /**
     * 超级群（2 万人量级）。客户端据此**隐藏**正在输入/已读双勾/成员在线态
     * 等在该规模下已关闭的能力，并改用分页拉成员。
     * **恒随群资料下发，端上不要自己按 member_count 猜。**
     */
    @SerialName("is_super") val isSuper: Boolean = false,
) {
    val iAmManager: Boolean
        get() = myRole == GroupMember.ROLE_OWNER || myRole == GroupMember.ROLE_ADMIN
}

/** 成员分页（`GET /groups/{id}/members`）。**成员数组在 `items` 不是 `members`。** */
@Serializable
data class GroupMembersPage(
    @SerialName("conv_id") val convId: String = "",
    val items: List<GroupMember> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable private data class GroupsResp(val groups: List<GroupInfo> = emptyList())

class GroupApi(private val http: HttpClient) {

    suspend fun myGroups(): List<GroupInfo> =
        decode(http.call("GET", "/api/v1/groups"), GroupsResp.serializer()).groups

    /** 群资料 + 成员。**超级群只回我自己**，成员表必须走 [members]。 */
    suspend fun info(convId: String): GroupInfo =
        decode(http.call("GET", "/api/v1/groups/$convId"), GroupInfo.serializer())

    /**
     * 成员目录游标分页（大群用）。
     * @param cursor 上页 next_cursor；空=首页
     * @param q 子串匹配群昵称/全局昵称/username
     */
    suspend fun members(convId: String, cursor: String = "", q: String = "", limit: Int = 50): GroupMembersPage {
        val query = buildMap {
            put("limit", limit.toString())
            if (cursor.isNotEmpty()) put("cursor", cursor)
            if (q.isNotEmpty()) put("q", q)
        }
        return decode(
            http.call("GET", "/api/v1/groups/$convId/members", query = query),
            GroupMembersPage.serializer(),
        )
    }

    suspend fun create(name: String, memberIds: List<String>): GroupInfo =
        decode(
            http.call("POST", "/api/v1/groups", buildJsonObject {
                put("name", name)
                putJsonArray("member_ids") { memberIds.forEach { add(it) } }
            }),
            GroupInfo.serializer(),
        )

    /** 退群（群主须先转让）。 */
    suspend fun leave(convId: String) {
        http.call("DELETE", "/api/v1/groups/$convId/members/me")
    }

    /** 邀请入群（任意成员可邀，除非群开了「仅管理员可邀请」）。 */
    suspend fun invite(convId: String, memberIds: List<String>) {
        http.call("POST", "/api/v1/groups/$convId/members", buildJsonObject {
            putJsonArray("member_ids") { memberIds.forEach { add(it) } }
        })
    }

    /** 我在本群的昵称。空=清除，回退全局昵称。 */
    suspend fun setMyNickname(convId: String, nickname: String) {
        http.call("PUT", "/api/v1/groups/$convId/members/me/nickname", buildJsonObject {
            put("nickname", nickname)
        })
    }
}
