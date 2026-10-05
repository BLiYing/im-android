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

    /** 通话记录（`{"cid","m","r","d"[,"g"]}`，设计见 IMServer `docs/design/CALL_RECORD_DESIGN.md`）。 */
    const val CALL = "call"

    /**
     * `msg_op` **事件行**（PROTOCOL §6.7）。不是聊天内容，是协议管道：
     * `content` 是自描述 JSON（= `msg_op` 上行负载），供离线端据此收敛撤回/编辑/置顶/删除。
     * **不入库为消息、不渲染、不计未读、不作会话列表预览**——判据在
     * [com.libeyond.imandroid.data.IncomingRule]。
     */
    const val MSG_OP = "msg_op"
}

/** send_msg 上行负载（PROTOCOL §4.1）。 */
/** [SendMsgData.replyTo] 的载荷。上行只带 conv_seq。 */
@Serializable
data class ReplyToData(@SerialName("conv_seq") val convSeq: Long)

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
    /**
     * 引用回复（M4-2）。**上行是嵌套对象 `reply_to: {conv_seq}`，不是扁平的
     * `reply_to_conv_seq`**——后者是**下行**字段名（`new_msg`/`sync_resp` 回带的那个）。
     *
     * 本端一直发的是扁平名，服务端读 `data.ReplyTo.ConvSeq` 读不到，**静默忽略**：
     * 菜单能点、输入栏引用条能显示、消息也发得出去，就是不带引用——
     * 一直到 2026-09-08 对着 iOS 核引用样式时，才发现服务端库里
     * `reply_to_conv_seq` 恒为 0。上下行同名不同形，是这类"看着做了其实没接上"的典型。
     *
     * 快照由服务端在发送时**冻结**（原消息后续被删/撤回仍可展示降级预览），所以上行只带 seq。
     */
    @SerialName("reply_to") val replyTo: ReplyToData? = null,
    /** 相册分组（§4.3 M4+）：同批多图共享，服务端只透传 + 限长 64。 */
    @SerialName("group_id") val groupId: String? = null,
    /**
     * @提及（§4.1，**仅群聊**，单聊携带会被服务端忽略）。三个字段一起走：
     * [mentions] 谁收到强提醒、[mentionAll] 是否 @所有人（仅群主/管理员，越权 300204）、
     * [mentionSpans] 每个 token 的位置（收端据此高亮，不必反查群成员表）。
     *
     * 服务端会按**当时群成员集**过滤 [mentions] 并封顶 64；[mentionSpans] 里 `user_id`
     * 不在过滤后的 mentions 里就**静默丢弃该片段**（安全边界：否则客户端能把正文里
     * 任意一段染成「@张三」并点进他的资料页）。
     */
    val mentions: List<String>? = null,
    @SerialName("mention_all") val mentionAll: Boolean? = null,
    @SerialName("mention_spans") val mentionSpans: List<MentionSpan>? = null,
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
    /**
     * 极小模糊缩略（M4-7，~20px 低质 JPEG 的 data URI）——收端在原图到位前放大 + 模糊当占位。
     * 生成与长度上限见 [com.libeyond.imandroid.data.TinyThumb]；**服务端上限 4096 rune 且会截断**，
     * 所以宁可不带也别带超长的（截断的 base64 解出来是一团噪声，比没有占位更糟）。
     */
    val thumb: String? = null,
    /**
     * 语音振幅指纹（§4.1，**仅 voice**；base64，原始字节 ≤120，每字节 0~100）。
     * 收端不下载音频就能画气泡波形；缺它退化成等高条纹。
     *
     * 服务端只做归一化（`protocol.SanitizeVoiceWaveform`：可 base64 解码且原始 ≤120 字节），
     * **非法/超长静默丢弃字段、不拒发**，非 voice 类型带上也会被丢。
     *
     * 本端一直没带这个字段，于是**转发语音会丢波形**（iOS `IMSocketManager` 一直在带）——
     * 2026-09-16 补上。本端自己还不能录音，所以它目前只在转发路径上有值。
     */
    val waveform: String? = null,
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
/** [MessageData.sysSegments] 的一段。`uid` 空 = 固定文案。 */
@Serializable
data class SysSegment(
    val uid: String = "",
    val text: String = "",
)

/**
 * 消息文本里的一段 **@ 提及**（PROTOCOL §4.1，对应 Telegram 的 `messageEntityMentionName`）。
 *
 * `uid` 为空串 = `@所有人`（**只高亮不可点**）。片段**覆盖整个 token（含前导 `@`）**，
 * 即 `text[offset]` 必然是 `@`；参照系是 `content_type=text` 看 `content`、其余看 `caption`。
 *
 * ⚠️ **`offset`/`length` 的单位是 UTF-16 码元**，与 iOS 的 `NSString`、JS 的 `String` 同源。
 * Kotlin 的 `String` 索引天生就是 UTF-16，零换算——**别改成码点**：`🎉@小明` 里 `@` 的
 * UTF-16 偏移是 2、码点偏移是 1、UTF-8 字节偏移是 4，一出现 emoji 就与另外两端和
 * 服务端校验全部对不上。判据与切段逻辑在 `data/Mention.kt`。
 */
@Serializable
data class MentionSpan(
    val offset: Int = 0,
    val length: Int = 0,
    @SerialName("user_id") val uid: String = "",
)

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
    /**
     * 极小模糊缩略（M4-7，~20px 低质 JPEG 的 data URI）——收端在原图到位前放大 + 模糊当占位。
     * 生成与长度上限见 [com.libeyond.imandroid.data.TinyThumb]；**服务端上限 4096 rune 且会截断**，
     * 所以宁可不带也别带超长的（截断的 base64 解出来是一团噪声，比没有占位更糟）。
     */
    val thumb: String? = null,
    val waveform: String? = null,
    /** 引用三件套（§4.3 M4-2）。 */
    @SerialName("reply_to_conv_seq") val replyToConvSeq: Long? = null,
    @SerialName("reply_snapshot") val replySnapshot: String? = null,
    @SerialName("reply_to_from") val replyToFrom: String? = null,
    /**
     * 引用快照结构化标记（P3 i18n，§4.3）：客户端按 App 语言重拼快照（`data/ReplySnapshots.kt`）。
     * 空 = 纯文本引用或老消息，显示 [replySnapshot] 原文。**必须落本地库**，同 [sysSegments]。
     */
    @SerialName("reply_snapshot_kind") val replySnapshotKind: String? = null,
    @SerialName("reply_snapshot_args") val replySnapshotArgs: Map<String, String>? = null,
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
    /**
     * 系统消息分段（PROTOCOL §6，2026-08-29）。`uid` 非空的那段是某人的名字，
     * **收端按本地口径重渲染**（备注 > 群昵称 > 昵称）并挂点击；空 = 固定文案原样显示。
     *
     * `content` 恒等于各段 `text` 顺序拼接，所以不认识这个字段的端照旧显示整句。
     * **必须落本地库**：不落的话刷新/重进会话后分段丢失，同一条消息退回"显真实昵称、不可点"。
     */
    @SerialName("sys_segments") val sysSegments: List<SysSegment>? = null,
    /**
     * 系统消息结构化事件（P3 i18n，PROTOCOL §6.6）：群系统消息与系统通知单聊（777000）带，
     * 客户端按 App 语言用本地模板重拼（`data/SysEvents.kt`）。空/不认识 = 回退 `content`/[sysSegments]。
     * [sysArgs] 是原始数据（群名、设备名、RFC3339 时间…）不是译文；人名不在这里，按顺序取 [sysSegments] 里带 uid 的段。
     * **必须落本地库**，同 [sysSegments]：不落的话重进会话后切了语言也还是中文。
     */
    @SerialName("sys_event") val sysEvent: String? = null,
    @SerialName("sys_args") val sysArgs: Map<String, String>? = null,
    /**
     * @提及片段（§4.1，仅群聊）。**必须落本地库**——不落的话重进会话后 @ 就不再高亮、
     * 点不动，与 [sysSegments] 同一个坑。
     *
     * 老消息 / 老客户端不带这个字段 → 收端回落"按本群昵称表扫文本"的老路
     * （见 `data/Mention.kt` 的 `segmentByNames`）。**编辑过的消息服务端会清空本字段**，
     * 因为偏移是相对原文的，留着会把新正文中间一段染成提及。
     */
    @SerialName("mention_spans") val mentionSpans: List<MentionSpan>? = null,
)

/**
 * group_read 下行负载（PROTOCOL §5.3）：本人视角的群「全员已读」位点（除我之外全体成员已读位点的最小值），
 * 只在变大时推。设计见 IMServer `docs/design/GROUP_READ_REALTIME_DESIGN.md`。
 */
@Serializable
data class GroupReadData(
    @SerialName("conv_id") val convId: String = "",
    @SerialName("group_read_seq") val groupReadSeq: Long = 0,
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
    /**
     * 本次愿意补拉的最大积压深度（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.4）：
     * 服务端算出 `head_conv_seq - since_conv_seq` 超过它就不查消息表，直接回 `too_long`。
     * **须是可空**，null（不带这个字段）与本项目自己发 `0` 语义不同——`null` 会退回老行为
     * "不限深度、追平为止"，Android 目前没有区间清单（C1），恒发 [SyncDefaults.MAX_GAP]，
     * 不区分超级群（那需要先知道会话是不是超级群，本地目前不落这一列，留作后续）。
     */
    @SerialName("max_gap") val maxGap: Long? = null,
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
    /**
     * 积压深度超过本游标声明的 [SyncCursorItem.maxGap]：`messages` 为空、`coveredConvSeq`
     * 原样等于请求的 `since`（游标不推进）。客户端据此在本地记一个缺口——本端目前没有区间清单
     * （OFFLINE_BACKLOG_DESIGN §4.11.1 C1，未做），先只做到"不再无限追平"，缺口本身还没有
     * 结构化记录，也就没有"按需开窗补"的下一步，留给 C1 落地时接上。
     */
    @SerialName("too_long") val tooLong: Boolean = false,
    /** 会话真实最新位点（含 `msg_op` 事件行）；仅当请求带了 `max_gap` 时下发，否则为 0。 */
    @SerialName("head_conv_seq") val headConvSeq: Long = 0,
)

/** [SyncCursorItem.maxGap] 的默认值——设计文档 §4.4："顺手补上"与"留缺口"的分水岭，三端同值。 */
object SyncDefaults {
    const val MAX_GAP = 400L
}

/**
 * `conv_bump` 的一项（超级群只推信号、不推全文，SUPERGROUP_DESIGN §5）：该会话**当前最新**位点 + 最新一条的极简预览。
 * 只为让会话列表立刻显示对的那一行；正文要等打开会话时才取。
 */
@Serializable
data class ConvBumpItem(
    @SerialName("conv_id") val convId: String = "",
    @SerialName("latest_seq") val latestSeq: Long = 0,
    val from: String = "",
    @SerialName("from_nickname") val fromNickname: String = "",
    val preview: String = "",
)

@Serializable
data class ConvBumpData(val items: List<ConvBumpItem> = emptyList())

@Serializable
data class SyncRespData(val conversations: List<SyncConversation> = emptyList())

/**
 * 按锚点开窗（`window_req` / `window_resp`，见
 * `../IMServer/docs/design/MESSAGE_WINDOW_DESIGN.md` §3.2）。
 *
 * **刻意不复用 `sync_req`**：sync 的语义是「按游标推进、覆盖区间、可推进本地位点」，
 * 窗口取数是「一次性快照，**不推进任何位点**」。混在一起会让 `covered_conv_seq` 的
 * "可安全推进游标"语义变含糊——那是同步正确性的核心。
 * 所以本端收到 `window_resp` **只落库、不动 syncedConvSeq**。
 */
@Serializable
data class WindowReqData(
    @SerialName("conv_id") val convId: String,
    /** 锚点 conv_seq；`0` = 最新（进会话用）。 */
    val anchor: Long,
    /** 锚点之前取多少条（不含锚点）。 */
    val before: Int,
    /** 锚点之后取多少条（不含锚点）。 */
    val after: Int,
)

@Serializable
data class WindowRespData(
    @SerialName("conv_id") val convId: String = "",
    /** 原样回显请求里的锚点（0=取最新）。`has_before=false` 记可见下界时，没有可渲染行就退回它。 */
    val anchor: Long = 0,
    /** conv_seq 升序，含锚点本身（若它对我可见）。 */
    val messages: List<MessageData> = emptyList(),
    /**
     * 锚点消息是否**存在且对我可见**。
     *
     * 这是这条协议的重点：它把「消息不存在/已删除」与「消息在，只是没在这一窗」分开。
     * 没有它就只能靠"翻了很多页还没见到"去猜，猜错就报一句假的「原消息已被删除」。
     */
    @SerialName("anchor_found") val anchorFound: Boolean = false,
    @SerialName("has_before") val hasBefore: Boolean = false,
    @SerialName("has_after") val hasAfter: Boolean = false,
)

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
    /** 批量删除广播帧不带（改看 [targets]），故有默认值——没有的话整帧反序列化失败。 */
    @SerialName("target_conv_seq") val targetConvSeq: Long = 0,
    /** 幂等键：重发命中即不重复应用。批量帧里是整单那个（可能省略）。 */
    @SerialName("client_msg_id") val clientMsgId: String = "",
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
    /**
     * 只出现在**批量「为所有人删除」的实时广播帧**（PROTOCOL §6.7.2）：一次批量每个成员只收这一帧，
     * 逐项给被删消息与其事件行的 conv_seq。离线 sync 的事件行仍是每条一行、单条形状。
     */
    val targets: List<MsgOpTarget>? = null,
) {
    /** 批量删除帧要删的 seq（按帧内顺序）；不是批量删除帧返回 null，按单条处理。与 im-web `batchDeleteTargetsOf` 同口径。 */
    fun batchDeleteSeqs(): List<Long>? =
        if (op == MsgOp.DELETE) targets?.map { it.targetConvSeq }?.filter { it > 0 } else null
}

/** 批量删除广播帧里的一项。 */
@Serializable
data class MsgOpTarget(
    @SerialName("target_conv_seq") val targetConvSeq: Long = 0,
    @SerialName("op_conv_seq") val opConvSeq: Long = 0,
)

/** conv_update 下行（§6.8）。**携带变更后的完整状态（非增量）**，收端直接覆盖本地。 */
@Serializable
data class ConvUpdateData(
    @SerialName("conv_id") val convId: String = "",
    /** settings | delete */
    val action: String = "",
    @SerialName("pinned_at") val pinnedAt: Long = 0,
    val muted: Boolean = false,
    /** 定时免打扰到期毫秒（0=永久或未免打扰，第二批 NOTIFICATIONS_P1_DESIGN §5.2）。 */
    @SerialName("mute_until") val muteUntil: Long = 0,
    @SerialName("marked_unread") val markedUnread: Boolean = false,
    /** 会话备注全值（G1，仅本人可见，settings 帧带；`""`=清除，缺键=非 settings 帧）。 */
    val remark: String = "",
    /** 仅 action=delete 带。 */
    @SerialName("cleared_at") val clearedAt: Long = 0,
)

/**
 * msg_hidden 下行（§6.7.1）：仅为我删除，收端**物理移除**该消息。
 * 批量隐藏合成一帧：`conv_seqs` 带全集、`conv_seq` 取首条；单条帧不带 `conv_seqs`。收端一律走 [seqs]。
 */
@Serializable
data class MsgHiddenData(
    @SerialName("conv_id") val convId: String = "",
    @SerialName("conv_seq") val convSeq: Long = 0,
    @SerialName("conv_seqs") val convSeqs: List<Long>? = null,
) {
    /** 要移除的 seq：优先 conv_seqs（批量帧），缺省退回 conv_seq（单条帧 / 老服务端）。 */
    fun seqs(): List<Long> = convSeqs?.filter { it > 0 } ?: listOfNotNull(convSeq.takeIf { it > 0 })
}

/**
 * voice_transcript 下行（§6.10），也是 `POST /voice/transcripts` REST 响应的 `data`（同一套字段，
 * 端上一套分支就够，见 `voice/VoiceTranscriber.kt`）。**只推给请求者本人**——别人没点「转文字」
 * 不会收到这一帧，他点的时候会命中服务端缓存秒出。带 `(convId, convSeq)` 而非音频路径：
 * 同一段音频可能在多个会话被请求转写，各自要按自己的坐标把结果贴回对的那条气泡。
 */
@Serializable
data class VoiceTranscriptData(
    @SerialName("conv_id") val convId: String = "",
    @SerialName("conv_seq") val convSeq: Long = 0,
    /** pending | done | failed */
    val status: String = "",
    val text: String = "",
    val lang: String = "",
)

/**
 * capabilities_update 下行（§6.9）：账号级能力（目前只有自动下载策略）有变。
 * **只带版本号**，不在帧里复制整份配置——收端据版本去重后重拉 `GET /api/v1/download-settings`。
 */
@Serializable
data class CapabilitiesUpdateData(
    val version: Long = 0,
)

/**
 * notify_settings_update 下行（§6.13，M5）：账号级通知设置（私聊/群聊 `{enabled,preview,sound}`
 * 与 `badge.include_muted`）有变。**只带版本号**，与 [CapabilitiesUpdateData] 是两条独立的版本序列，
 * 不要混用——收端据版本去重后重拉 `GET /api/v1/notify-settings`（`AccountNotifySettingsStore`）。
 */
@Serializable
data class NotifySettingsUpdateData(
    val version: Long = 0,
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

/**
 * app_state 上行（§6.12，M5）：告诉服务端本连接所在的 App 是否在前台，服务端据此判断
 * 要不要给这台设备发离线推送（FCM）。`state` 只有 `"foreground"`/`"background"` 两个合法值，
 * 无回执，帧丢了只会退化成「60 秒心跳超时前不推」，不必重发。
 */
@Serializable
data class AppStateData(val state: String)

/** error 下行（§8）。 */
@Serializable
data class ErrorData(
    val code: Int = 0,
    val message: String = "",
    /** 当错误是对某条 send_msg 的拒绝时带上——客户端据此把那条消息标为失败。 */
    @SerialName("client_msg_id") val clientMsgId: String? = null,
)
