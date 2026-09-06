package com.libeyond.imandroid.sdk.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 帧类型常量——**唯一权威是后端 `internal/protocol/envelope.go` 的常量**
 * （`../IMServer/docs/PROTOCOL.md` §2 明说以代码为准；那份文档正文里的枚举行就漏了
 * `conv_bump` 与 `voice_transcript`，照文档抄会缺两个）。
 *
 * 新增类型只追加、向后兼容。
 */
object FrameType {
    const val PING = "ping"
    const val PONG = "pong"
    const val AUTH = "auth"
    const val SEND_MSG = "send_msg"
    const val ACK = "ack"
    const val NEW_MSG = "new_msg"
    const val RECEIPT = "receipt"
    const val TYPING = "typing"
    const val PRESENCE = "presence"
    const val WATCH = "watch"
    const val SYNC_REQ = "sync_req"
    const val SYNC_RESP = "sync_resp"
    const val FRIEND = "friend"
    const val GROUP = "group"
    const val MSG_OP = "msg_op"
    const val CONV_UPDATE = "conv_update"
    const val CAPABILITIES_UPDATE = "capabilities_update"
    const val CONV_BUMP = "conv_bump"
    const val WINDOW_REQ = "window_req"
    const val WINDOW_RESP = "window_resp"
    const val MSG_HIDDEN = "msg_hidden"
    const val VOICE_TRANSCRIPT = "voice_transcript"
    const val ERROR = "error"
}

/** `msg_op` 的 op 取值（PROTOCOL §6.7）。均为追加式事件，不物理改原消息。 */
object MsgOp {
    const val RECALL = "recall"
    const val EDIT = "edit"
    const val PIN = "pin"
    /** 为所有人删除：收端物理移除、不留墓碑，区别于 recall。 */
    const val DELETE = "delete"
}

/**
 * 信封——所有上下行帧的统一外层结构（PROTOCOL §2）。
 *
 * `data` 保持为未解析的 [JsonElement]：**先按 [type] 判定，再解成对应负载**，
 * 与后端 `json.RawMessage` 的延迟解析同构。这样收到未知 type 时
 * 不会因为解不动负载而抛异常——那正是下面这条红线要的。
 *
 * `seq` 是**客户端本地单调自增**，只用于把响应配对回请求，**不等于消息顺序号**
 * （消息顺序看 `conv_seq`）。
 */
@Serializable
data class Envelope(
    val type: String,
    val seq: Long = 0,
    val data: JsonElement? = null,
)

/** 判定该内容类型能否携带图说 caption（Telegram 模型：仅媒体/文件）。 */
fun contentTypeAllowsCaption(contentType: String): Boolean =
    contentType == "image" || contentType == "video" || contentType == "file"

/**
 * 协议 JSON 编解码器。
 *
 * `ignoreUnknownKeys = true` 是**协议红线**，不是图省事的开关：
 * PROTOCOL §2 规定「客户端收到未知 type 必须忽略、不崩」，服务端加字段是向后兼容的常规动作。
 * 关掉它会让「后端新增一个字段」当场变成老客户端全线解析失败。
 *
 * `encodeDefaults = false` 对齐 Go 侧的 `omitempty`：默认值不上线，
 * 免得把 `seq: 0`、空串这类无意义字段发给服务端。
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = false
}
