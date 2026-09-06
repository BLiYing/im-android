package com.libeyond.imandroid.sdk.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 内容类型（PROTOCOL §4.1）。`voice`=录制的语音条；`audio` 一词保留给音乐文件，暂未实现。 */
object ContentType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val VOICE = "voice"
    const val FILE = "file"
    const val SYSTEM = "system"
    const val CHAT_RECORD = "chat_record"
    const val CONTACT = "contact"
}

/** send_msg 上行负载（PROTOCOL §4.1）。 */
@Serializable
data class SendMsgData(
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("conv_id") val convId: String,
    val to: String,
    @SerialName("content_type") val contentType: String,
    val content: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    val caption: String? = null,
    @SerialName("reply_to_conv_seq") val replyToConvSeq: Long? = null,
)

/** ack 下行负载（PROTOCOL §4.2）——对 send_msg 的确认。 */
@Serializable
data class AckData(
    @SerialName("client_msg_id") val clientMsgId: String = "",
    @SerialName("server_msg_id") val serverMsgId: String = "",
    @SerialName("conv_id") val convId: String = "",
    @SerialName("conv_seq") val convSeq: Long = 0,
    val timestamp: Long = 0,
)

/**
 * new_msg / sync_resp.messages[] 的单条消息（PROTOCOL §4.3）。
 *
 * 字段大多 `omitempty`——**缺字段按未知处理，不得为了拿尺寸或时长去预下载媒体**（§4.3 明文）。
 */
@Serializable
data class MessageData(
    @SerialName("server_msg_id") val serverMsgId: String = "",
    @SerialName("conv_id") val convId: String = "",
    @SerialName("conv_seq") val convSeq: Long = 0,
    val from: String = "",
    @SerialName("from_nickname") val fromNickname: String? = null,
    /** 群主/管理员才下发（owner/admin），用于气泡身份徽标的兜底。 */
    @SerialName("from_role") val fromRole: String? = null,
    @SerialName("content_type") val contentType: String = ContentType.TEXT,
    val content: String = "",
    val caption: String? = null,
    val timestamp: Long = 0,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("media_w") val mediaW: Int? = null,
    @SerialName("media_h") val mediaH: Int? = null,
    val duration: Int? = null,
    val waveform: String? = null,
    /** 引用三件套（§4.3 M4-2）。 */
    @SerialName("reply_to_conv_seq") val replyToConvSeq: Long? = null,
    @SerialName("reply_snapshot") val replySnapshot: String? = null,
    @SerialName("reply_to_from") val replyToFrom: String? = null,
    /** 状态列：撤回 / 为所有人删除 / 编辑 / 置顶。 */
    @SerialName("recalled_at") val recalledAt: Long? = null,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("edited_at") val editedAt: Long? = null,
    @SerialName("pinned_at") val pinnedAt: Long? = null,
)

/** receipt 上下行负载（PROTOCOL §5）。 */
@Serializable
data class ReceiptData(
    @SerialName("conv_id") val convId: String,
    /** 仅下行广播时有值：回执来自谁。上行不带。 */
    val from: String? = null,
    /** `delivered` | `read` */
    val status: String,
    @SerialName("up_to_conv_seq") val upToConvSeq: Long,
) {
    companion object {
        const val DELIVERED = "delivered"
        const val READ = "read"
    }
}

/** sync_req 上行（PROTOCOL §6.1）。 */
@Serializable
data class SyncCursorItem(
    @SerialName("conv_id") val convId: String,
    @SerialName("since_conv_seq") val sinceConvSeq: Long,
)

@Serializable
data class SyncReqData(val cursors: List<SyncCursorItem>)

/** sync_resp 下行的单个会话增量（PROTOCOL §6.2）。 */
@Serializable
data class SyncConversation(
    @SerialName("conv_id") val convId: String = "",
    val messages: List<MessageData> = emptyList(),
    /**
     * 本页**实际下发消息**里的最大序号，诊断/对账用。
     * **禁止用它推进游标**——见 [com.libeyond.imandroid.data.SyncCursorRule]。
     */
    @SerialName("latest_conv_seq") val latestConvSeq: Long = 0,
    /**
     * 权威已覆盖位点：`(since, covered]` 内每个序号要么已下发、要么对你不可见。
     * **游标推进的唯一依据**。老服务端不带此字段时为 0。
     */
    @SerialName("covered_conv_seq") val coveredConvSeq: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SyncRespData(val conversations: List<SyncConversation> = emptyList())

/** typing（§5.5）。 */
@Serializable
data class TypingData(
    @SerialName("conv_id") val convId: String,
    val from: String? = null,
)

/** error 下行（§8）。 */
@Serializable
data class ErrorData(
    val code: Int = 0,
    val message: String = "",
    /** 当错误是对某条 send_msg 的拒绝时带上——客户端据此把那条消息标为失败。 */
    @SerialName("client_msg_id") val clientMsgId: String? = null,
)
