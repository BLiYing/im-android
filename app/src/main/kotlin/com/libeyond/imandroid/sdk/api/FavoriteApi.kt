package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.http.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 收藏（M4-4）。**内容快照**存到服务端：原消息之后被撤回 / 删除，收藏里仍在。
 *
 * 本端目前只有「加」这一个口子（多选底栏的收藏钮）；收藏列表页、从收藏发送都还没做。
 * 字段口径与 iOS `IMHTTPService addFavoriteWithToken:` 逐项一致——**媒体元数据要跟着走**：
 * 不带宽高收藏页只能按正方形猜框，不带时长语音画成 0:00，不带波形退化成等高条纹。
 */
class FavoriteApi(private val http: HttpClient) {

    suspend fun add(draft: FavoriteDraft) {
        http.call("POST", "/api/v1/favorites", draft.toJson())
    }
}

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
