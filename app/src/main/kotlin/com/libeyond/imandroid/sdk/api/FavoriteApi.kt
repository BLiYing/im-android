package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 收藏（M4-4）。**内容快照**存到服务端：原消息之后被撤回 / 删除，收藏里仍在。
 *
 * 加（长按菜单 / 多选底栏 / 查看器「更多」）、列表（「我 ▸ 收藏消息」与聊天页「从收藏发送」共用）、删除三件事。
 * 字段口径与 iOS `IMHTTPService addFavoriteWithToken:` 逐项一致——**媒体元数据要跟着走**：
 * 不带宽高收藏页只能按正方形猜框，不带时长语音画成 0:00，不带波形退化成等高条纹。
 */
class FavoriteApi(private val http: HttpClient) {

    suspend fun add(draft: FavoriteDraft) {
        http.call("POST", "/api/v1/favorites", draft.toJson())
    }

    /**
     * 一页收藏（新在前，按 id 倒序）。**按已加载条数作 offset 往后翻**（同 iOS `favoritesWithToken:offset:`）：
     * 服务端不传 limit 时一页 100 条；老实现不分页、超 100 条就静默截断再也看不到更旧的。
     */
    suspend fun list(offset: Int = 0): FavoritePage {
        val query = if (offset > 0) mapOf("offset" to offset.toString()) else emptyMap()
        return decode(http.call("GET", "/api/v1/favorites", query = query), FavoritePage.serializer())
    }

    /** 删一条（owner 限定，服务端校验）。 */
    suspend fun delete(id: Long) {
        http.call("DELETE", "/api/v1/favorites/$id")
    }
}

/**
 * 收藏列表里的一条（服务端 `handlers_favorite.go` 的 `handleListFavorites` 输出）。
 *
 * **是内容快照，不是消息引用**：原消息撤回 / 删除之后它还在。`source_*` 只作溯源展示，
 * 不能拿去跳回原消息（原消息可能已经没了，FAVORITES_DESIGN §8.2 第 3 条）。
 */
@Serializable
data class Favorite(
    val id: Long = 0,
    @SerialName("content_type") val contentType: String = "",
    val content: String = "",
    val caption: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    /** 视频/语音时长，毫秒。 */
    val duration: Int = 0,
    val waveform: String = "",
    val thumb: String = "",
    val poster: String = "",
    @SerialName("media_w") val mediaW: Int = 0,
    @SerialName("media_h") val mediaH: Int = 0,
    @SerialName("source_conv_id") val sourceConvId: String = "",
    @SerialName("source_conv_seq") val sourceConvSeq: Long = 0,
    /** 原发送者 uid。**不上屏**——展示名由调用方解析（好友备注 / 昵称），解析不出宁可空着。 */
    @SerialName("source_from") val sourceFrom: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class FavoritePageMeta(val total: Int = 0, val limit: Int = 0, val offset: Int = 0)

@Serializable
data class FavoritePage(
    val favorites: List<Favorite> = emptyList(),
    val page: FavoritePageMeta = FavoritePageMeta(),
)

/** 一条收藏的快照（对齐服务端 `handlers_favorite.go` 的请求体）。 */
data class FavoriteDraft(
    val contentType: String,
    val content: String,
    val caption: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    /** 视频/语音时长，毫秒。 */
    val duration: Int? = null,
    val waveform: String? = null,
    val thumb: String? = null,
    val poster: String? = null,
    val mediaW: Int? = null,
    val mediaH: Int? = null,
    val sourceConvId: String,
    val sourceConvSeq: Long,
    /** 原发送者 uid——服务端据此换算展示名，**不上屏**。 */
    val sourceFrom: String,
) {
    /** 空值一律省略（同 iOS：只在有值时写键），别让服务端把 `""` 当成真的值存下。 */
    fun toJson(): JsonObject = buildJsonObject {
        put("content_type", contentType.ifBlank { "text" })
        put("content", content)
        put("source_conv_id", sourceConvId)
        put("source_conv_seq", sourceConvSeq)
        put("source_from", sourceFrom)
        caption?.takeIf { it.isNotEmpty() }?.let { put("caption", it) }
        fileName?.takeIf { it.isNotEmpty() }?.let { put("file_name", it) }
        fileSize?.takeIf { it > 0 }?.let { put("file_size", it) }
        duration?.takeIf { it > 0 }?.let { put("duration", it) }
        waveform?.takeIf { it.isNotEmpty() }?.let { put("waveform", it) }
        thumb?.takeIf { it.isNotEmpty() }?.let { put("thumb", it) }
        poster?.takeIf { it.isNotEmpty() }?.let { put("poster", it) }
        mediaW?.takeIf { it > 0 }?.let { put("media_w", it) }
        mediaH?.takeIf { it > 0 }?.let { put("media_h", it) }
    }

    companion object {
        fun of(m: MessageEntity) = FavoriteDraft(
            contentType = m.contentType, content = m.content, caption = m.caption,
            fileName = m.fileName, fileSize = m.fileSize, duration = m.duration, waveform = m.waveform,
            thumb = m.thumb, poster = m.poster, mediaW = m.mediaW, mediaH = m.mediaH,
            sourceConvId = m.convId, sourceConvSeq = m.convSeq, sourceFrom = m.sender,
        )
    }
}
