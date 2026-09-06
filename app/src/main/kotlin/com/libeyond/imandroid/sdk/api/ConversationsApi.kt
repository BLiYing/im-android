package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 会话摘要（对齐后端 `internal/conversation.Summary`）。
 *
 * **不下发 `username`** 是刻意的：会话列表语义是"最近聊过的"，标识没有意义，
 * 且它是全端最高频接口，不为展示不到的字段变宽（PROTOCOL §11）。
 */
@Serializable
data class ConversationSummary(
    @SerialName("conv_id") val convId: String = "",
    @SerialName("is_group") val isGroup: Boolean = false,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    /** 超级群：不显示在线绿点 / 不显示已读双勾 / 收到的是轻量 bump 而非全文。 */
    @SerialName("is_super") val isSuper: Boolean = false,
    val peer: String = "",
    @SerialName("peer_nickname") val peerNickname: String = "",
    @SerialName("peer_remark") val peerRemark: String = "",
    @SerialName("peer_avatar_url") val peerAvatarUrl: String = "",
    @SerialName("peer_presence") val peerPresence: String = "",
    @SerialName("peer_online_until") val peerOnlineUntil: Long = 0,
    @SerialName("peer_last_seen") val peerLastSeen: Long = 0,
    @SerialName("last_message") val lastMessage: MessageData? = null,
    @SerialName("latest_conv_seq") val latestConvSeq: Long = 0,
    val unread: Int = 0,
    /** unread 撞到服务端上限，真实值 ≥ 它。据此显示「N+」而不是一律 99+。 */
    @SerialName("unread_capped") val unreadCapped: Boolean = false,
    @SerialName("read_seq") val readSeq: Long = 0,
    @SerialName("peer_read_seq") val peerReadSeq: Long = 0,
    @SerialName("group_read_seq") val groupReadSeq: Long = 0,
    @SerialName("pinned_at") val pinnedAt: Long = 0,
    val muted: Boolean = false,
    @SerialName("marked_unread") val markedUnread: Boolean = false,
    /** 未读区间内有人 @我。**穿透免打扰**做强提醒。 */
    @SerialName("mention_unread") val mentionUnread: Boolean = false,
    /** 会话备注名（G1，仅本人可见）。与 peer_remark 不同：这是"我对这个会话"的别名，群聊也适用。 */
    val remark: String = "",
)

/**
 * 部署级配额/能力（`GET /server-config`）。
 * **客户端不得硬编码群上限**，一律读这里（与账号级 capabilities 不是一回事）。
 */
@Serializable
data class ServerConfig(
    @SerialName("max_group_members") val maxGroupMembers: Int = 0,
    @SerialName("supergroup_enabled") val supergroupEnabled: Boolean = false,
    @SerialName("max_supergroup_members") val maxSupergroupMembers: Int = 0,
)

@Serializable
private data class ConversationsResp(
    val conversations: List<ConversationSummary> = emptyList(),
)

class ConversationsApi(private val http: HttpClient) {

    suspend fun list(): List<ConversationSummary> =
        decode(http.call("GET", "/api/v1/conversations"), ConversationsResp.serializer()).conversations

    /**
     * 会话设置（§6.8）。**整体替换**三项——不是增量，漏传一项等于把它清零。
     * 成功后服务端推 conv_update 给本人全部设备，本端也从帧里收敛。
     */
    suspend fun updateSettings(convId: String, pinnedAt: Long, muted: Boolean, markedUnread: Boolean) {
        http.call("PUT", "/api/v1/conversations/$convId/settings", buildJsonObject {
            put("pinned_at", pinnedAt)
            put("muted", muted)
            put("marked_unread", markedUnread)
        })
    }

    /**
     * 删除会话。**不物理删消息**：记 cleared_at + deleted=1，会话从列表隐藏，
     * 对方再发消息即复现，复现后仅新消息计未读。
     */
    suspend fun delete(convId: String) {
        http.call("DELETE", "/api/v1/conversations/$convId")
    }

    suspend fun serverConfig(): ServerConfig =
        decode(http.call("GET", "/api/v1/server-config"), ServerConfig.serializer())

    /** 「仅为我删除」一条消息（§6.7.1）。服务端随后推 msg_hidden 给本人全部设备。 */
    suspend fun hideMessage(convId: String, convSeq: Long) {
        http.call("POST", "/api/v1/messages/hide", buildJsonObject {
            put("conv_id", convId)
            put("conv_seq", convSeq)
        })
    }
}
