package com.libeyond.imandroid.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * 消息本地表。
 *
 * ## 主键为什么是三元组
 * `(ownerUid, convId, convSeq)` ——
 * - **`ownerUid` 必须在主键里**：一台设备可以登录多个账号，两个账号看到的同一个 `conv_seq`
 *   完全是两条不同的消息。iOS 与 Web 都按 `(owner_uid, conv_id)` 隔离持久化，本端同构。
 *   漏掉它 = 切账号后互相污染，且切回来时数据已被覆盖。
 * - **`convSeq` 作幂等键**：sync 重复补拉靠它收敛（PROTOCOL §6.2「宁可重拉，不能漏拉」）。
 *
 * ## 待发消息怎么办
 * 还没拿到 ack 的消息 `convSeq = 0`，多条待发会主键冲突。故待发消息**不进这张表**，
 * 走 [PendingMessageEntity]；ack 到达后再以真实 convSeq 落进这里。
 * 这与 iOS 把待发消息也塞进同一张表（用 conv_seq=0）不同——iOS 因此长期需要
 * 「conv_seq==0 的行不参与去重/排序」这类特判，本端从结构上避开。
 */
@Entity(
    tableName = "message",
    primaryKeys = ["ownerUid", "convId", "convSeq"],
    indices = [Index(value = ["ownerUid", "convId", "timestamp"])],
)
data class MessageEntity(
    val ownerUid: String,
    val convId: String,
    val convSeq: Long,
    val serverMsgId: String = "",
    val clientMsgId: String = "",
    val sender: String = "",
    val fromNickname: String? = null,
    val fromRole: String? = null,
    val contentType: String = "text",
    val content: String = "",
    val caption: String? = null,
    val timestamp: Long = 0,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mediaW: Int? = null,
    val mediaH: Int? = null,
    val duration: Int? = null,
    /**
     * 视频封面 URL（§4.1）。**不是锦上添花**：解不了 HEVC 的端只能靠这张封面显示视频，
     * 没有它就是一片黑底加个播放钮。
     */
    val poster: String? = null,
    val waveform: String? = null,
    val replyToConvSeq: Long? = null,
    val replySnapshot: String? = null,
    val replyToFrom: String? = null,
    val recalledAt: Long? = null,
    val deletedAt: Long? = null,
    val editedAt: Long? = null,
    val pinnedAt: Long? = null,
    /** 转发自（显示名快照，公开名）。见 `MessageData.forwardFrom` 的两条纪律。 */
    val forwardFrom: String? = null,
    /** 相册分组 ID：同批多图共享，聚簇成宫格用。 */
    val groupId: String? = null,
)

/** 发送态。 */
enum class SendState { Sending, Failed }

/**
 * 待发 / 发送失败的消息。**与已确认消息分表**，理由见 [MessageEntity]。
 *
 * 主键用 `clientMsgId`——它就是幂等键（PROTOCOL §3），服务端也靠它去重。
 */
@Entity(
    tableName = "pending_message",
    primaryKeys = ["ownerUid", "clientMsgId"],
    indices = [Index(value = ["ownerUid", "convId", "createdAt"])],
)
data class PendingMessageEntity(
    val ownerUid: String,
    val clientMsgId: String,
    val convId: String,
    val to: String,
    val contentType: String,
    val content: String,
    val caption: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val replyToConvSeq: Long? = null,
    /** 转发自（公开名快照）。**必须落库**：否则杀进程后重发的那一条会丢掉「转发自」。 */
    val forwardFrom: String? = null,
    /**
     * 相册分组 ID。**待发消息就要带**——iOS 是「选完秒上屏」直接成宫格，
     * 不等 ack。只在确认消息上聚簇的话，用户会看见 N 张图先各自排一列、
     * 收到 ack 后再"啪"地拼成宫格。
     */
    val groupId: String? = null,
    /**
     * 媒体元数据。**待发就要存**——ack 不回带这些字段，收到 ack 时只能从待发行里取
     * （与 [forwardFrom] / [groupId] 同一个坑，这已经是第三次了：
     * 不存的话视频在**自己这一侧**没封面、没时长、气泡比例也不对，而对端一切正常）。
     */
    val mediaW: Int? = null,
    val mediaH: Int? = null,
    val duration: Int? = null,
    val poster: String? = null,
    /** [SendState] 的 name。 */
    val state: String = "Sending",
    val createdAt: Long = 0,
    /** 失败时的业务码，供 UI 显示原因（如 300004 被禁言）。 */
    val errorCode: Int = 0,
)

/**
 * 会话本地快照 + **同步游标**。
 *
 * `syncedConvSeq` 是「已连续处理完成的位置」，推进规则见
 * [com.libeyond.imandroid.data.SyncCursorRule] —— 只认服务端的 `covered_conv_seq`。
 * **不得**用本地 `MAX(convSeq)` 或会话最新序号代替：它们证明不了中间没有空洞。
 */
@Entity(tableName = "conversation", primaryKeys = ["ownerUid", "convId"])
data class ConversationEntity(
    val ownerUid: String,
    val convId: String,
    val isGroup: Boolean = false,
    /** 单聊对端 uid；群聊为空。 */
    val peerUid: String = "",
    val title: String = "",
    val avatarUrl: String = "",
    /** 单聊对端备注名（仅本人可见）。 */
    val peerRemark: String = "",
    val lastContent: String = "",
    val lastContentType: String = "text",
    val lastTimestamp: Long = 0,
    val lastConvSeq: Long = 0,
    val unread: Int = 0,
    /**
     * 未读区间内有人 @我（含 @所有人），仅群聊有意义（M4-8）。
     * **穿透免打扰**：muted 也要强提醒，故它不能被 muted 一笔带过。
     */
    val mentionUnread: Boolean = false,
    /** 我已读到的位点。 */
    val readSeq: Long = 0,
    /** 对端已读到的位点（单聊已读双勾用）。 */
    val peerReadSeq: Long = 0,
    /** **连续同步游标**，见类注释。 */
    val syncedConvSeq: Long = 0,
    val pinnedAt: Long = 0,
    val muted: Boolean = false,
    val markedUnread: Boolean = false,
)
