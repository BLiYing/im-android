package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
    /** 群待审入群申请数（G3，仅群主/管理员下发，普通成员省略）：会话列表红字「[N 待审]」前缀。 */
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("pinned_at") val pinnedAt: Long = 0,
    val muted: Boolean = false,
    /**
     * 定时免打扰到期毫秒（0=永久或未免打扰，NOTIFICATIONS_P1_DESIGN §5）。
     * **`muted` 恒为服务端按当前时刻算好的有效值**（已到期 → `muted=false, mute_until=0`）——
     * 但端上仍要用 [com.libeyond.imandroid.data.MuteState.isMutedNow] 重判一遍：本地这份是上次
     * 刷新时的快照，两次刷新之间时间还在走，到期由端上自己判（§4.3）。
     */
    @SerialName("mute_until") val muteUntil: Long = 0,
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

/** `GET /conversations/{id}/settings` 的响应——对称于 [ConversationsApi.updateSettings]。 */
@Serializable
data class ConversationSettings(
    @SerialName("pinned_at") val pinnedAt: Long = 0,
    val muted: Boolean = false,
    /** 见 [ConversationSummary.muteUntil] 的同一条注释。 */
    @SerialName("mute_until") val muteUntil: Long = 0,
    @SerialName("marked_unread") val markedUnread: Boolean = false,
    val remark: String = "",
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
     * 读取本人对某会话的会话级设置（对称于 [updateSettings]，`GET` 版）。
     *
     * **群资料页专用**：本地 `ConversationEntity` 只落了 `pinnedAt`/`muted`/`markedUnread`
     * 三项，没有落 `remark`（那是给单聊 `peerRemark` 留的位置，两者是两个字段），
     * 群资料页的「置顶聊天/消息免打扰/群备注」三行进页时都从这里现拉，不读本地缓存。
     */
    suspend fun settings(convId: String): ConversationSettings =
        decode(http.call("GET", "/api/v1/conversations/$convId/settings"), ConversationSettings.serializer())

    /**
     * 会话设置（§6.8）。**整体替换**三项——不是增量，漏传一项等于把它清零。
     * 成功后服务端推 conv_update 给本人全部设备，本端也从帧里收敛。
     *
     * @param muteUntil 定时免打扰到期毫秒（第二批，NOTIFICATIONS_P1_DESIGN §5.2）。**可省略**
     *   （不传，即不落进请求体，`null`）：服务端若本次 `muted=true` 且库里当前是「未到期的定时
     *   免打扰」，**保留原到期时间**——改置顶/标未读时把 `muted` 原样带回却不带这个参数的调用方
     *   （[updateSettings] 的绝大多数调用点）**不会**把定时免打扰变成永久。选时长菜单的调用点才
     *   显式传值；`muted=false` 时服务端一律清 0，传不传都一样。
     */
    suspend fun updateSettings(convId: String, pinnedAt: Long, muted: Boolean, markedUnread: Boolean, muteUntil: Long? = null) {
        http.call("PUT", "/api/v1/conversations/$convId/settings", buildJsonObject {
            put("pinned_at", pinnedAt)
            put("muted", muted)
            put("marked_unread", markedUnread)
            if (muteUntil != null) put("mute_until", muteUntil)
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
    suspend fun media(convId: String, kind: String, cursor: Long = 0, limit: Int = 60, clearedUpTo: Long = 0, after: Long = 0): ConvMediaPage {
        val query = buildMap {
            put("kind", kind)
            put("limit", limit.toString())
            // after = 向更新方向（升序，紧挨 after 的最近 limit 条），与 cursor（向更旧）互斥
            if (after > 0) put("after", after.toString()) else if (cursor > 0) put("cursor", cursor.toString())
        }
        return decode(
            http.call("GET", "/api/v1/conversations/$convId/media", query = query),
            ConvMediaPage.serializer(),
        ).let { if (after > 0) ConvQueryFloor.mediaNewer(it, clearedUpTo) else ConvQueryFloor.media(it, clearedUpTo) }
    }

    /**
     * 会话内消息检索（G4，PROTOCOL §11）。
     *
     * **只在本地有缺口且在线时才走这里**（判据 [com.libeyond.imandroid.data.ChatSearch.pickSource]）：
     * 本地齐全时问服务端没有任何好处，还慢。
     *
     * 服务端按 `conv_seq` **倒序**回，上限 50 条一页；`has_more` 为真表示命中更多，
     * 端上要把计数补 `+`，▲ 翻过最旧一条时用 `next_cursor` 再取一页（`SearchPaging`）。
     * [clearedUpTo]（本机清空位点，设计 §6.7）以内的命中在这里滤掉，见 [ConvQueryFloor]。
     * 命中口径（text content / caption / file_name 子串、排除撤回删除、尊重入群下界）由服务端保证，
     * 端上**不要**再判一遍——判据分叉的表现是"搜索结果和聊天页对不上"。
     */
    suspend fun searchMessages(
        convId: String,
        q: String,
        from: String = "",
        cursor: Long = 0,
        limit: Int = 50,
        clearedUpTo: Long = 0,
    ): ConvSearchPage {
        val query = buildMap {
            put("q", q)
            put("limit", limit.toString())
            if (from.isNotEmpty()) put("from", from)
            if (cursor > 0) put("cursor", cursor.toString())
        }
        return decode(
            http.call("GET", "/api/v1/conversations/$convId/messages/search", query = query),
            ConvSearchPage.serializer(),
        ).let { ConvQueryFloor.search(it, clearedUpTo) }
    }

    /**
     * 会话日历（日历跳转，对齐 iOS `IMHTTPService convCalendarWithToken:`）：按「本地日」聚合
     * `[fromMs, toMs)` 区间内的消息分布，`utcOffsetMs` 是端上的时区偏移（东八区 = 28800000）。
     *
     * **只在本地有缺口且在线时才用得上**：本地齐全时"哪些天有消息"直接查本地库更快
     * （见 `data/MessageWindowQueries.firstConvSeqAtOrAfter` 的用法说明）；有缺口时目标那天的
     * 消息可能整天都在缺口里，只有服务端能给出权威答案——**日历接口存在的唯一理由就是这个**。
     * 服务端对跨度有上限（约 13 个月），端上一次性拉近两年会被拒；调用方按需求收窄区间。
     */
    suspend fun calendar(convId: String, fromMs: Long, toMs: Long, utcOffsetMs: Long, clearedUpTo: Long = 0): ConvCalendarResult =
        decode(
            http.call(
                "GET", "/api/v1/conversations/$convId/calendar",
                query = mapOf("from" to fromMs.toString(), "to" to toMs.toString(), "utc_offset_ms" to utcOffsetMs.toString()),
            ),
            ConvCalendarResult.serializer(),
        ).let { ConvQueryFloor.calendar(it, clearedUpTo) }

    /**
     * 会话备注（G1，仅本人可见、多端同步）。与 [updateSettings] 解耦——PUT 是各自独立的
     * 接口，改备注不动置顶/免打扰三开关，拨开关也不清备注（服务端把当前 remark 原样带回）。
     * 留空即清除备注，恢复显示真实群名 / 对端昵称。单聊群聊都适用（不是好友备注，
     * 好友备注走 `ContactsApi.setRemark` 的 `POST /friends/remark`，两者是两回事）。
     */
    suspend fun setRemark(convId: String, remark: String) {
        http.call("PUT", "/api/v1/conversations/$convId/remark", buildJsonObject {
            put("remark", remark)
        })
    }

    suspend fun delete(convId: String) {
        http.call("DELETE", "/api/v1/conversations/$convId")
    }

    suspend fun serverConfig(): ServerConfig =
        decode(http.call("GET", "/api/v1/server-config"), ServerConfig.serializer())

    /**
     * 多选「仅删除自己」（§6.7.1 批量，≤[BatchDelete.MAX]）：一次请求，服务端逐条回成败；
     * 随后推**一帧** msg_hidden（带 conv_seqs）给本人全部设备。整单失败（断网/非成员）抛异常。
     */
    suspend fun hideMessages(convId: String, convSeqs: List<Long>): List<BatchItemResult> =
        decode(http.call("POST", "/api/v1/messages/hide", buildJsonObject {
            put("conv_id", convId)
            putJsonArray("conv_seqs") { convSeqs.forEach { add(it) } }
        }), BatchResults.serializer()).results

    /**
     * 多选「为所有人删除」（§6.7.2，≤[BatchDelete.MAX]）：规则与单条 WS msg_op delete 同源，逐条回成败
     * （300005 不存在 / 300006 无权）。成功项服务端合成**一帧** msg_op{op:delete, targets} 广播（§6.7.2）。
     * [clientMsgId] 是整单幂等键：重试不会重复登记事件行。整单失败抛异常。
     */
    suspend fun deleteMessagesForEveryone(convId: String, convSeqs: List<Long>, clientMsgId: String): List<BatchItemResult> =
        decode(http.call("POST", "/api/v1/messages/delete", buildJsonObject {
            put("conv_id", convId)
            putJsonArray("conv_seqs") { convSeqs.forEach { add(it) } }
            put("client_msg_id", clientMsgId)
        }), BatchResults.serializer()).results

    /** 「仅为我删除」一条消息（§6.7.1）。服务端随后推 msg_hidden 给本人全部设备。 */
    suspend fun hideMessage(convId: String, convSeq: Long) {
        http.call("POST", "/api/v1/messages/hide", buildJsonObject {
            put("conv_id", convId)
            put("conv_seq", convSeq)
        })
    }

    /**
     * 本会话的置顶消息（**最近置顶在前**，服务端上限 50；已撤回 / 为所有人删除 / 我「仅删除自己」的已滤掉）。
     * 单聊群聊都有。**不吃 `history_visible` 入群下界**——后入群的人也看得到入群前的置顶（决策 19），
     * 所以其中的消息常不在本地。`conv_seq <= 0` 的脏项丢掉（跳不过去）。
     */
    suspend fun pinned(convId: String): List<PinnedMessage> =
        decode(http.call("GET", "/api/v1/conversations/$convId/pinned"), PinnedResp.serializer())
            .items.filter { it.convSeq > 0 }
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

/**
 * 会话内检索结果的一项（后端 `conversation.SearchMessage`）。
 * 字段是「列表行渲染 + 跳转」所需的最小集：带 `conv_seq` 就够跳到消息本体，不带引用快照/媒体元数据。
 */
@Serializable
data class ConvSearchItem(
    @SerialName("conv_seq") val convSeq: Long = 0,
    @SerialName("server_msg_id") val serverMsgId: String = "",
    val sender: String = "",
    /** 仅群聊填（空则回退 uid，与 new_msg 同约定）。 */
    @SerialName("from_nickname") val fromNickname: String = "",
    @SerialName("content_type") val contentType: String = "",
    val content: String = "",
    val caption: String = "",
    val timestamp: Long = 0,
)

@Serializable
data class ConvSearchPage(
    @SerialName("conv_id") val convId: String = "",
    val items: List<ConvSearchItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** 日历一格（`GET /conversations/{id}/calendar`）。无消息的天**不出现**——服务端按缺席表达灰格。 */
@Serializable
data class ConvCalendarDay(
    @SerialName("day_start_ms") val dayStartMs: Long = 0,
    val count: Int = 0,
    @SerialName("first_conv_seq") val firstConvSeq: Long = 0,
)

@Serializable
data class ConvCalendarResult(
    @SerialName("conv_id") val convId: String = "",
    /** 按日升序。 */
    val days: List<ConvCalendarDay> = emptyList(),
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

/** `GET /conversations/{id}/pinned` 的一项（PROTOCOL §11）。`from_nickname` 只有群聊项带。 */
@Serializable
data class PinnedMessage(
    @SerialName("conv_seq") val convSeq: Long = 0,
    @SerialName("server_msg_id") val serverMsgId: String = "",
    val sender: String = "",
    @SerialName("from_nickname") val fromNickname: String = "",
    @SerialName("content_type") val contentType: String = "text",
    val content: String = "",
    /** 图说；协议表里没列但 iOS 读它（带 caption 的图/视频/文件横幅显示那句话）。 */
    val caption: String = "",
    val timestamp: Long = 0,
    @SerialName("pinned_at") val pinnedAt: Long = 0,
)

@Serializable
private data class PinnedResp(val items: List<PinnedMessage> = emptyList(), val total: Int = 0)
