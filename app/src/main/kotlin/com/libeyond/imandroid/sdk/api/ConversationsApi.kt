package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
private data class ConversationsResp(
    val conversations: List<ConversationSummary> = emptyList(),
)

class ConversationsApi(private val http: HttpClient) {

    suspend fun list(): List<ConversationSummary> =
        decode(http.call("GET", "/api/v1/conversations"), ConversationsResp.serializer()).conversations
}
