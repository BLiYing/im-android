package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 用户名片（对齐 `internal/profile.Card`）。 */
@Serializable
data class UserCard(
    /** 内部 ID（10 位数字）。**只作接口参数与本地键，UI 全程不展示。** */
    @SerialName("user_id") val userId: String = "",
    /** 公开句柄，UI 里渲染成 `@xxx`。系统账号为空。 */
    val username: String = "",
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val phone: String = "",
    val tags: List<String> = emptyList(),
    /** 查看者对该用户的私有备注名；看自己时恒空。 */
    val remark: String = "",
    val presence: String = "",
    @SerialName("online_until") val onlineUntil: Long = 0,
    @SerialName("last_seen") val lastSeen: Long = 0,
) {
    /**
     * 本机显示名。回退链 **备注 → 昵称 → @句柄 → 「未命名用户」**，
     * **末级绝不是 user_id**（那是 10 位随机内部 ID，露在界面上对用户毫无意义）。
     */
    val displayName: String
        get() = remark.ifBlank { nickname }.ifBlank { username.ifBlank { "" }.let { if (it.isEmpty()) "" else "@$it" } }
            .ifBlank { Str.s(R.string.common_unnamed_user) }

    /** 副标题里的标识行。**为空时整行隐藏**，不显示「用户名：未设置」，更不回退内部 ID。 */
    val handle: String get() = if (username.isBlank()) "" else "@$username"
}

/** 好友关系项（对齐 `internal/friend.Entry`）。 */
@Serializable
data class FriendEntry(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val nickname: String = "",
    /** 我对该好友的私有备注名，显示优先级高于昵称。 */
    val remark: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    /** accepted | pending | requested | blocked */
    val status: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
    /** 与 status 正交：我是否把对方加入黑名单。拉黑的好友 status 仍是 accepted。 */
    val blocked: Boolean = false,
    /** 好友申请的验证消息，只在 pending/requested 时有值。 */
    val hello: String = "",
) {
    val displayName: String
        get() = remark.ifBlank { nickname }.ifBlank { if (username.isBlank()) Str.s(R.string.common_unnamed_user) else "@$username" }

    val handle: String get() = if (username.isBlank()) "" else "@$username"

    companion object {
        const val ACCEPTED = "accepted"
        /** 别人申请加我，等我确认。 */
        const val PENDING = "pending"
        /** 我申请加别人，等对方确认。 */
        const val REQUESTED = "requested"
        const val BLOCKED = "blocked"
    }
}

@Serializable private data class FriendsResp(val friends: List<FriendEntry> = emptyList())
@Serializable private data class UsersResp(val users: List<UserCard> = emptyList())

class ContactApi(private val http: HttpClient) {

    /** @param status 空=全部；accepted / pending / requested / blocked */
    suspend fun friends(status: String = ""): List<FriendEntry> {
        val q = if (status.isEmpty()) emptyMap() else mapOf("status" to status)
        return decode(http.call("GET", "/api/v1/friends", query = q), FriendsResp.serializer()).friends
    }

    /**
     * 找人。**按 username（大小写不敏感）/ phone 精确匹配，防枚举**——
     * 内部 `user_id` 不是找人键，别拿它当搜索词。
     */
    suspend fun search(q: String, limit: Int = 20): List<UserCard> =
        decode(
            http.call("GET", "/api/v1/users/search", query = mapOf("q" to q, "limit" to limit.toString())),
            UsersResp.serializer(),
        ).users

    suspend fun card(userId: String): UserCard =
        decode(http.call("GET", "/api/v1/users/$userId"), UserCard.serializer())

    suspend fun me(): UserCard =
        decode(http.call("GET", "/api/v1/users/me"), UserCard.serializer())

    /** @param hello 验证消息，≤50 rune；服务端压单行 + 超长截断而非报错。
     * @return 是否已直接成为好友（outcome=accepted）。 */
    suspend fun request(userId: String, hello: String = ""): Boolean {
        val data = http.call("POST", "/api/v1/friends/request", buildJsonObject {
            put("user_id", userId)
            if (hello.isNotBlank()) put("hello", hello)
        })
        // outcome=accepted：直接成为好友（对方先申请过我 / 我曾单向删除而对方仍视我为好友）——
        // 调用方此时**不得**提示「已发送好友申请」（PROTOCOL §6.5）。老服务端不回该字段 = requested
        return ((data as? kotlinx.serialization.json.JsonObject)?.get("outcome") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content == "accepted"
    }

    suspend fun accept(userId: String) = act("accept", userId)
    suspend fun reject(userId: String) = act("reject", userId)
    suspend fun block(userId: String) = act("block", userId)
    suspend fun unblock(userId: String) = act("unblock", userId)

    private suspend fun act(action: String, userId: String) {
        http.call("POST", "/api/v1/friends/$action", buildJsonObject { put("user_id", userId) })
    }

    suspend fun remove(userId: String) {
        http.call("DELETE", "/api/v1/friends/$userId")
    }

    /**
     * 举报（`POST /api/v1/reports`）。
     *
     * @param targetType `user`（举报这个人）/ `message`（举报某条消息）——两个入口互补，
     *   资料页那个是**针对人本身**的，聊天页长按那个是针对一条消息的，别合并掉
     *   （iOS 2026-09-06 合并消息侧两项时差点把人侧整个丢了）。
     * @param reason 可空；服务端不强制。
     */
    suspend fun report(targetType: String, targetId: String, convId: String = "", reason: String = "") {
        http.call("POST", "/api/v1/reports", buildJsonObject {
            put("target_type", targetType)
            put("target_id", targetId)
            put("conv_id", convId)
            put("reason", reason)
        })
    }

    /**
     * 多选批量举报**同一个人的若干条消息**：一次一张工单（`target_type=message` + `target_seqs`）。
     *
     * 带了 `target_seqs` 服务端就不看 `target_id`，但字段仍要非空，填第一条的 seq（iOS/Web 同）。
     * 能不能举报（全是同一个人、没有我自己的、都已确认）由调用方先判——见 `SelectionActions.reportableSender`；
     * 服务端单张上限 100 条，与多选上限同数。
     */
    suspend fun reportMessages(convId: String, seqs: List<Long>, reason: String = "") {
        require(seqs.isNotEmpty()) { "reportMessages needs at least one seq" }
        http.call("POST", "/api/v1/reports", buildJsonObject {
            put("target_type", "message")
            put("target_id", seqs.first().toString())
            put("conv_id", convId)
            putJsonArray("target_seqs") { seqs.forEach { add(it) } }
            put("reason", reason)
        })
    }

    /**
     * 批量取资料（`POST /users/batch`，单次 ≤100 个、每账号 60 次/分）。返回的卡片**不含 phone/在线态/备注**
     * （备注是查看者私有数据，端上自己叠）。`missing` 是查无此人的 uid，给负缓存用——不是错误。
     */
    suspend fun usersBatch(ids: List<String>): com.libeyond.imandroid.data.ProfileBatch {
        val data = http.call("POST", "/api/v1/users/batch", buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } })
        val r = decode(data, UsersBatchResp.serializer())
        return com.libeyond.imandroid.data.ProfileBatch(r.users, r.missing)
    }

    /** 设备注名。空串=清除。须已是好友，否则 200103。 */
    suspend fun setRemark(userId: String, remark: String) {
        http.call("POST", "/api/v1/friends/remark", buildJsonObject {
            put("user_id", userId)
            put("remark", remark)
        })
    }
}

@Serializable
private data class UsersBatchResp(val users: List<UserCard> = emptyList(), val missing: List<String> = emptyList())
