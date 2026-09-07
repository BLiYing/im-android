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
    /** 相册分组（§4.3 M4+）：同批多图共享，服务端只透传 + 限长 64。 */
    @SerialName("group_id") val groupId: String? = null,
    /**
     * 转发溯源（§4.3 M4-3）：**发送时冻结的"转发自"显示名**，限长 40。
     *
     * 两条纪律，任一条破了都是线上事故：
     * ① **只能是公开名**——这个字符串会原样发给收件人。带备注就是把"我给他起的外号"
     *    发出去（im-web 与 iOS 都为此各出过一次事故，见 docs/UI.md 隐私红线）。
     * ② **转发链保留最初作者**——转发一条已被转发的消息，仍写最初作者而不是中间人
     *    （对端口径：`m.forwardFrom || m.fromNickname || m.from`）。
     */
    @SerialName("forward_from") val forwardFrom: String? = null,
    /**
     * 媒体元数据（§4.1，2026-08-03 起服务端支持；本端 2026-09-07 补上）。
     * 收端据 `media_w/media_h` **按原比例预留气泡**（免加载完跳版）、据 `duration`
     * 在视频封面角标显 `mm:ss`。服务端只透传 + 范围校验：**负数直接拒发 `100001`**，
     * 所以拿不到就传 null（不要传 -1），0 的语义是「未知」。
     */
    @SerialName("media_w") val mediaW: Int? = null,
    @SerialName("media_h") val mediaH: Int? = null,
    /** 视频时长（毫秒）。非 video 带上会被服务端丢弃。 */
    val duration: Int? = null,
    /**
     * 视频封面 URL（§4.1，限长 512）。**由发送端抽首帧上传后填这里**。
     * 不是锦上添花：解不了 HEVC 的浏览器只能靠这张封面显示 iPhone 拍的视频。
     */
    val poster: String? = null,
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
    /**
     * 视频封面 URL（§4.1）。**入站也要收**——不收的话 iOS/Web 发来的视频在本端
     * 同样只有一片黑底加播放钮（2026-09-07 之前就是这样，一直没人发现，
     * 因为本端此前根本发不了视频、也就很少收到）。
     */
    val poster: String? = null,
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
    /** 转发溯源显示名（§4.3 M4-3）；气泡上方显示「转发自 X」。 */
    @SerialName("forward_from") val forwardFrom: String? = null,
    /**
     * 相册分组（§4.3 M4+）：同批发出的多图/多视频共享一个客户端生成的 ID。
     * **每张仍是独立消息**（可单独撤回/引用/转发/收藏），客户端把同组聚簇成宫格。
     */
    @SerialName("group_id") val groupId: String? = null,
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

/**
 * msg_op 上下行（§6.7）。对既有消息的操作，**只追加事件**：
 * 服务端不物理改/删原消息，而是更新派生状态列 + 登记事件行 + 广播本帧。
 */
@Serializable
data class MsgOpData(
    /** recall | edit | pin | delete */
    val op: String,
    @SerialName("conv_id") val convId: String,
    @SerialName("target_conv_seq") val targetConvSeq: Long,
    /** 幂等键：重发命中即不重复应用。 */
    @SerialName("client_msg_id") val clientMsgId: String,
    /** 仅 edit。 */
    val content: String? = null,
    /**
     * 仅 pin。**下行恒带此字段（非 omitempty）**——取消置顶时 false 若被省略，
     * 端上分不清「取消」与「未带」，会残留已置顶态。
     */
    val pinned: Boolean? = null,
    /** 下行追加：本事件自身的 conv_seq，供离线端排序。 */
    @SerialName("op_conv_seq") val opConvSeq: Long = 0,
    /** 下行追加：操作者。 */
    val by: String = "",
    val timestamp: Long = 0,
)

/** conv_update 下行（§6.8）。**携带变更后的完整状态（非增量）**，收端直接覆盖本地。 */
@Serializable
data class ConvUpdateData(
    @SerialName("conv_id") val convId: String = "",
    /** settings | delete */
    val action: String = "",
    @SerialName("pinned_at") val pinnedAt: Long = 0,
    val muted: Boolean = false,
    @SerialName("marked_unread") val markedUnread: Boolean = false,
    /** 仅 action=delete 带。 */
    @SerialName("cleared_at") val clearedAt: Long = 0,
)

/** msg_hidden 下行（§6.7.1）：仅为我删除，收端**物理移除**该消息。 */
@Serializable
data class MsgHiddenData(
    @SerialName("conv_id") val convId: String = "",
    @SerialName("conv_seq") val convSeq: Long = 0,
)

/** presence 下行广播（§5.5 租约模型）。 */
@Serializable
data class PresenceFrame(
    val user: String = "",
    val status: String = "",
    /** 在线租约到期毫秒；仅 status=online 时下发。 */
    @SerialName("online_until") val onlineUntil: Long = 0,
    /** 最后在线毫秒；0=未知/不可见。 */
    @SerialName("last_seen") val lastSeen: Long = 0,
)

/** watch 上行（§5.5）：当前要显示在线态的 uid 全集，**全量替换**。 */
@Serializable
data class WatchData(val set: List<String> = emptyList())

/** error 下行（§8）。 */
@Serializable
data class ErrorData(
    val code: Int = 0,
    val message: String = "",
    /** 当错误是对某条 send_msg 的拒绝时带上——客户端据此把那条消息标为失败。 */
    @SerialName("client_msg_id") val clientMsgId: String? = null,
)
