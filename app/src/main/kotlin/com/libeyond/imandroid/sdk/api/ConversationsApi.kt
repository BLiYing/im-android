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

    /**
     * 链接富预览。**抓不到不是错误**——服务端抓空 / 限流 / 网络失败一律回 null，
     * UI 退化成纯链接文本（与 iOS `linkPreviewWithToken:` 的 nil 语义同）。
     *
     * 调用方**必须自己做缓存与去重**：这个接口与 `/qr/resolve` 共享每账号 60/min，
     * 一屏十条链接消息各请求一次就能把配额打光。
     */
    suspend fun linkPreview(url: String): LinkPreview? = runCatching {
        val q = java.net.URLEncoder.encode(url, "UTF-8")
        decode(http.call("GET", "/api/v1/link-preview?url=$q"), LinkPreview.serializer())
    }.getOrNull()?.takeIf { it.isRenderable }

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
    /**
     * 会话媒体归档分页（详情页「媒体 / 文件」，M4.5-3）。
     *
     * `kind` 见 [MediaKind]。**按 conv_seq 倒序（新在前）**，游标就是上页的 `next_cursor`。
     * 服务端已经把撤回 / 为所有人删除 / 「仅为我删除」 / history_visible 下界都滤掉了，
     * 端上**不要**再自己判一遍——判据分叉的话，详情页里会出现聊天页看不到的消息。
     *
     * **「链接」这一格服务端不覆盖**：链接不是独立的 content_type，是从文本里识别出来的，
     * 服务端没有可索引的列（`internal/conversation/media.go` 开头写明了）。
     */
    suspend fun media(convId: String, kind: String, cursor: Long = 0, limit: Int = 60): ConvMediaPage {
        val query = buildMap {
            put("kind", kind)
            put("limit", limit.toString())
            if (cursor > 0) put("cursor", cursor.toString())
        }
        return decode(
            http.call("GET", "/api/v1/conversations/$convId/media", query = query),
            ConvMediaPage.serializer(),
        )
    }

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

/** 链接富预览（PROTOCOL §11 `GET /api/v1/link-preview`）。字段都可能缺。 */
/** [ConversationsApi.media] 的 kind。**服务端未知 kind 回空集，不报错**，所以别拼错。 */
object MediaKind {
    /** 图片 + 视频混排。查看器左右翻页要的就是这一条序列，分两次拉再归并得处理两条游标。 */
    const val MEDIA = "media"
    const val FILE = "file"
    const val VOICE = "voice"
}

/** 会话媒体列表的一项。字段 = 缩略渲染 + 打开 + 跳回聊天所需的最小集。 */
@Serializable
data class ConvMediaItem(
    @SerialName("conv_seq") val convSeq: Long = 0,
    val sender: String = "",
    @SerialName("content_type") val contentType: String = "",
    /** 媒体/文件的 URL（相对路径，端上按自己连的 host 补全）。 */
    val content: String = "",
    val caption: String = "",
    val timestamp: Long = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    val poster: String = "",
    @SerialName("media_w") val mediaW: Int = 0,
    @SerialName("media_h") val mediaH: Int = 0,
    val duration: Int = 0,
    @SerialName("group_id") val groupId: String = "",
    /**
     * 极小模糊缩略（M4-7）——**服务端这个接口一直在下发**（`internal/conversation/media.go`
     * 的 `Thumb`），本端此前没解析，于是详情页宫格每一格都从空底开始加载。
     */
    val thumb: String = "",
)

@Serializable
data class ConvMediaPage(
    val items: List<ConvMediaItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class LinkPreview(
    val url: String = "",
    val title: String = "",
    val description: String = "",
    val image: String = "",
    @SerialName("site_name") val siteName: String = "",
) {
    /** 一张卡都撑不起来时不出卡（与 iOS/Web 同：只有 url 没有标题的卡等于噪音）。 */
    val isRenderable: Boolean get() = title.isNotBlank() || description.isNotBlank() || image.isNotBlank()
}
