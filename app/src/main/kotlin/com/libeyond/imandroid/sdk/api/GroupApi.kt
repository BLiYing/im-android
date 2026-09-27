package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
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
    /**
     * 群主的公开资料。**只有 `GET /groups`（群列表）会下发这两个字段**，
     * 单群详情不带——群列表的副标题是「群主 X」，而 `owner` 是 10 位随机内部 ID，
     * 直接渲染就是内部 ID 露到界面上（ACCOUNT_IDENTITY_REDESIGN §7.5 明令禁止）。
     */
    @SerialName("owner_nickname") val ownerNickname: String = "",
    @SerialName("owner_username") val ownerUsername: String = "",
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
    /**
     * G2 群治理开关组（PROTOCOL §11）。**名字都是「仅管理员可…」，不是「允许成员…」**：
     * `true` = 收紧。读反了两条判据就会同时错向两边（既让无权者看到入口，
     * 又让有权者看不到）——2026-09-08 就是这么错的。
     *
     * **五个必须都在**：`PUT /groups/{id}/settings` 是**整体替换**，
     * 读不回来的那几个在下一次改任意一项时会被当成 false 写回去，
     * 等于悄悄替群主关掉他设过的开关。
     */
    @SerialName("join_approval") val joinApproval: Boolean = false,
    @SerialName("perm_invite") val permInvite: Boolean = false,
    @SerialName("perm_edit_info") val permEditInfo: Boolean = false,
    @SerialName("perm_pin") val permPin: Boolean = false,
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

/**
 * 待审入群申请（G3，`GET /groups/{id}/join-requests`）。
 *
 * `status`：`pending` | `approved` | `rejected`。**已处理的也会回**（拉全量时），
 * 端上分「待处理 / 已处理」两段——只显待处理的话，审批完那一下列表会空掉，
 * 看着像操作没生效。
 */
@Serializable
data class JoinRequest(
    @SerialName("user_id") val userId: String = "",
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    /** 申请人填的验证消息。 */
    val hello: String = "",
    val status: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
) {
    val isPending: Boolean get() = status == "pending"

    /** 列表显示名。**末级绝不是 user_id**（那是 10 位内部 ID）。 */
    val displayName: String get() = nickname.ifBlank { Str.s(R.string.common_unnamed_user) }
}

@Serializable private data class JoinRequestsResp(val requests: List<JoinRequest> = emptyList())

/** 群黑名单的一项（G2）。`expiresAt=0` 表示永久。 */
@Serializable
data class GroupBan(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("banned_by") val bannedBy: String = "",
    @SerialName("banned_at") val bannedAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
) {
    /** 显示名。**末级绝不是 user_id**（那是 10 位内部 ID）。 */
    val displayName: String
        get() = nickname.ifBlank { if (username.isBlank()) Str.s(R.string.common_unnamed_user) else "@$username" }

    val isPermanent: Boolean get() = expiresAt <= 0
}

@Serializable private data class GroupBansResp(val bans: List<GroupBan> = emptyList())

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

    suspend fun create(name: String, memberIds: List<String>, avatarUrl: String = ""): GroupInfo =
        decode(
            http.call("POST", "/api/v1/groups", buildJsonObject {
                put("name", name)
                put("avatar_url", avatarUrl)
                putJsonArray("member_ids") { memberIds.forEach { add(it) } }
            }),
            GroupInfo.serializer(),
        )

    /**
     * 凭群码/邀请链接入群（G3，接收方半）。[token] 可以是完整链接或裸 token——服务端自己摘。
     * 需审批时服务端回 `300210`（[com.libeyond.imandroid.sdk.protocol.ErrCode.GROUP_JOIN_PENDING]），
     * **不是失败**：申请已落库，等 `join_result` 帧回来；调用方按码分支，别当异常兜底处理。
     */
    suspend fun join(token: String, hello: String = ""): GroupInfo =
        decode(
            http.call("POST", "/api/v1/groups/join", buildJsonObject {
                put("token", token)
                put("hello", hello)
            }),
            GroupInfo.serializer(),
        )

    /**
     * 解散群（**仅群主**）。服务端向全体广播 dissolve，各端据此移除会话。
     * 与 [leave] 是两件事：退群只影响我自己，解散是把群本身删掉，不可撤销。
     */
    suspend fun dissolve(convId: String) {
        http.call("DELETE", "/api/v1/groups/$convId")
    }

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
    // ————————————— 群管理写操作（G1/G2，PROTOCOL §11）—————————————
    //
    // 权限判定**不在这里做**，全部走 `GroupPermissions`（纯函数、已单测）。
    // 在 API 层各判各的，早晚会和 UI 层的按钮显隐分叉——那时表现为
    // 「按钮亮着但点了报 300204」或反过来，两种都很难自查。
    // 服务端仍是权威：端上放行的操作被拒时按业务码提示即可。

    /** 改群资料（群主/管理员）。整体替换，传空串即清空该字段。 */
    suspend fun updateInfo(convId: String, name: String, avatarUrl: String, intro: String) {
        http.call("PUT", "/api/v1/groups/$convId", buildJsonObject {
            put("name", name); put("avatar_url", avatarUrl); put("intro", intro)
        })
    }

    /** 发布/撤下群公告（≤500；**空串 = 撤下**）。发布会落一条系统消息。 */
    suspend fun setAnnouncement(convId: String, text: String) {
        http.call("PUT", "/api/v1/groups/$convId/announcement", buildJsonObject { put("text", text) })
    }

    /** 全员禁言：`0` 解除 / `-1` 永久 / 其余为到期毫秒。 */
    suspend fun setMuteAll(convId: String, until: Long) {
        http.call("PUT", "/api/v1/groups/$convId/mute", buildJsonObject { put("until", until) })
    }

    /** 单独禁言某成员，同一套 until 口径。**须严格高于对方**，否则服务端拒。 */
    suspend fun muteMember(convId: String, uid: String, until: Long) {
        http.call("PUT", "/api/v1/groups/$convId/members/$uid/mute", buildJsonObject { put("until", until) })
    }

    /**
     * 移除成员。`ban` 三档：
     * - `none` 只移出（还能再进）
     * - `cooldown` 缺省，24h 冷却
     * - `forever` 永久黑名单
     */
    suspend fun removeMember(convId: String, uid: String, ban: String = "cooldown") {
        http.call("DELETE", "/api/v1/groups/$convId/members/$uid?ban=$ban")
    }

    /** 设/撤管理员（**仅群主**）。role 取 `admin` / `member`。 */
    suspend fun setRole(convId: String, uid: String, role: String) {
        http.call("PUT", "/api/v1/groups/$convId/members/$uid/role", buildJsonObject { put("role", role) })
    }

    /** 转让群主（**仅群主**；原群主降为普通成员，不可撤销）。 */
    suspend fun transferOwner(convId: String, uid: String) {
        http.call("POST", "/api/v1/groups/$convId/transfer", buildJsonObject { put("user_id", uid) })
    }

    /**
     * 黑名单（群主/管理员，G2）。**数组在 `bans`**——本仓群相关接口的数组名各不相同
     * （`members` / `items` / `requests` / `bans`），照直觉写会静默拿到空列表。
     */
    suspend fun bans(convId: String): List<GroupBan> =
        decode(http.call("GET", "/api/v1/groups/$convId/bans"), GroupBansResp.serializer()).bans

    /** 解除拉黑（把人从群黑名单里放出来，之后才能再入群）。 */
    suspend fun unban(convId: String, uid: String) {
        http.call("DELETE", "/api/v1/groups/$convId/bans/$uid")
    }

    /** 群治理开关组。**整体替换**——少传一个就是把它设成 false。 */
    suspend fun updateSettings(
        convId: String,
        joinApproval: Boolean,
        permInvite: Boolean,
        permEditInfo: Boolean,
        permPin: Boolean,
        historyVisible: Boolean,
    ) {
        http.call("PUT", "/api/v1/groups/$convId/settings", buildJsonObject {
            put("join_approval", joinApproval)
            put("perm_invite", permInvite)
            put("perm_edit_info", permEditInfo)
            put("perm_pin", permPin)
            put("history_visible", historyVisible)
        })
    }

    /**
     * 待审入群申请（群主/管理员）。`status` 留空 = 全部（待处理 + 已处理）。
     *
     * **数组在 `requests`**（不是 `items`、也不是 `members`）——本仓三个群相关接口
     * 三个不同的数组名，照直觉写会静默拿到空列表（`GroupInfo.members` 的注释里记着
     * 2026-08-31 那次两端同时踩中）。
     */
    suspend fun joinRequests(convId: String, status: String = ""): List<JoinRequest> {
        val query = if (status.isBlank()) emptyMap() else mapOf("status" to status)
        return decode(
            http.call("GET", "/api/v1/groups/$convId/join-requests", query = query),
            JoinRequestsResp.serializer(),
        ).requests
    }

    /** 审批入群申请。action 取 `approve` / `reject`。 */
    suspend fun reviewJoinRequest(convId: String, uid: String, approve: Boolean) {
        http.call("POST", "/api/v1/groups/$convId/join-requests/$uid", buildJsonObject {
            put("action", if (approve) "approve" else "reject")
        })
    }

    suspend fun setMyNickname(convId: String, nickname: String) {
        http.call("PUT", "/api/v1/groups/$convId/members/me/nickname", buildJsonObject {
            put("nickname", nickname)
        })
    }
}
