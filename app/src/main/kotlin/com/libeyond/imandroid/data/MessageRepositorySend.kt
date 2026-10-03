package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.data.db.SendState
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.protocol.ErrorData
import java.util.UUID

// 待发行的创建/更新与 ack/拒绝的落地。从 MessageRepository 搬出来只为控体量
// （那边贴着 600 行硬闸，CODING_STYLE §7②）——写成扩展函数，调用点（`repo.createPending(...)` 等）
// 一个字没改，与 MessageWindowQueries.kt / MessageSync.kt 同一套做法。

/** 更新待发消息的正文（上传完把本地 uri 换成服务端 url）。 */
suspend fun MessageRepository.updatePendingContent(owner: String, cid: String, content: String, fileSize: Long?) {
    val p = pending.byClientId(owner, cid) ?: return
    pending.put(p.copy(content = content, fileSize = fileSize ?: p.fileSize))
}

/** 生成一条待发消息并落库。调用方拿返回值去发帧。 */
suspend fun MessageRepository.createPending(
    owner: String,
    convId: String,
    to: String,
    content: String,
    contentType: String = ContentType.TEXT,
    replyToConvSeq: Long? = null,
    forwardFrom: String? = null,
    groupId: String? = null,
    /**
     * 文件名 / 大小 / 图说。**必须在这里就落库**，不能只在首次发帧时当参数传：
     * [MessageService.resend] 是从待发行里读这些字段的，行里没有就等于
     * 「重发一次，文件名和大小就没了」——与 forwardFrom / groupId / 媒体元数据
     * 同一个坑（见 AckCarryOver 的注释），这已经是第四次。
     * 另外文件待发气泡要靠 fileName 显示名字，不然屏幕上是一串 content:// 。
     */
    fileName: String? = null,
    fileSize: Long? = null,
    caption: String? = null,
    mediaW: Int? = null,
    mediaH: Int? = null,
    duration: Int? = null,
    poster: String? = null,
    thumb: String? = null,
    /** 语音振幅指纹（仅 voice）。转发语音要靠它，见 [PendingMessageEntity.waveform]。 */
    waveform: String? = null,
    /** @提及片段的 JSON（见 [PendingMessageEntity.mentionSpans]）。 */
    mentionSpans: String? = null,
    /** 被 @ 的 uid 列表 JSON（见 [PendingMessageEntity.mentions]，重名成员反推不出来）。 */
    mentions: String? = null,
    /** 固定的幂等键。只有通话记录用（`call-<call_id>`，服务端凭它去重）；其余都是随机 UUID。 */
    clientMsgId: String = UUID.randomUUID().toString(),
): PendingMessageEntity {
    val p = PendingMessageEntity(
        ownerUid = owner,
        clientMsgId = clientMsgId,
        convId = convId,
        to = to,
        contentType = contentType,
        content = content,
        replyToConvSeq = replyToConvSeq,
        forwardFrom = forwardFrom,
        groupId = groupId,
        fileName = fileName,
        fileSize = fileSize,
        caption = caption,
        mediaW = mediaW,
        mediaH = mediaH,
        duration = duration,
        poster = poster,
        thumb = thumb,
        waveform = waveform,
        mentionSpans = mentionSpans,
        mentions = mentions,
        state = SendState.Sending.name,
        createdAt = System.currentTimeMillis(),
    )
    pending.put(p)
    log.i("msg_pending_created", "convId" to convId, "cid" to p.clientMsgId)
    return p
}

/**
 * 补齐待发行的媒体元数据（宽高/时长/封面）。
 *
 * 为什么需要它：整批媒体的待发行是在**压缩/抽帧之前**就落库的（宫格要在点「发送」
 * 那一刻就成形），而宽高与时长要等解码才知道。**不回写就是 resend 丢字段**——
 * [MessageService.resend] 从待发行读这些值，与 forwardFrom / groupId / fileName
 * 同一个坑（见 AckCarryOver 的注释），这已经是第五次。
 */
suspend fun MessageRepository.updatePendingMedia(
    owner: String,
    cid: String,
    mediaW: Int? = null,
    mediaH: Int? = null,
    duration: Int? = null,
    poster: String? = null,
    thumb: String? = null,
    waveform: String? = null,
) {
    val p = pending.byClientId(owner, cid) ?: return
    pending.put(
        p.copy(
            mediaW = mediaW ?: p.mediaW,
            mediaH = mediaH ?: p.mediaH,
            duration = duration ?: p.duration,
            poster = poster ?: p.poster,
            thumb = thumb ?: p.thumb,
            waveform = waveform ?: p.waveform,
        ),
    )
}

/**
 * 把一条待发行标回「发送中」（语音重传前置步骤，见 [MediaSendPipeline.reuploadVoice]）。
 *
 * [MessageService.transmit] 从不改状态——它假定调用方在这之前已经是 Sending。
 * 语音重传是从 Failed 状态**重新触发一次上传**（不是补发已上传的帧），
 * 不先复位就会一直停在红❗，即使上传其实正在悄悄跑。
 */
suspend fun MessageRepository.markPendingSending(owner: String, cid: String) {
    pending.markState(owner, cid, SendState.Sending.name, 0)
}

/**
 * 给一条**已确认**的消息补上极小缩略（老消息补种）。
 *
 * 为什么需要：`thumb` 是随消息走的，本端接这个字段之前收发的、以及三端历史消息
 * 都没有——协议里明写**服务端不做回溯**。但客户端可以：原图在本地已经有了之后，
 * 自己算一张缩略存起来，**下次进这个会话就有磨砂占位了**。
 * 只补本机，不上行（那条消息在服务端的字节不该被后来的客户端改写）。
 */
suspend fun MessageRepository.setThumb(owner: String, convId: String, convSeq: Long, thumb: String) {
    val m = messages.byConvSeq(owner, convId, convSeq) ?: return
    if (!m.thumb.isNullOrBlank()) return
    messages.upsert(m.copy(thumb = thumb))
}

/**
 * 按会话坐标查一条已确认消息。**语音转文字的 WS 回执**（`voice_transcript` 帧只带
 * `conv_id`/`conv_seq`，不带音频路径）靠它反查 `content` 才知道该把文本落进哪个缓存键
 * （[com.libeyond.imandroid.voice.VoiceTranscriber]）。
 */
suspend fun MessageRepository.messageAt(owner: String, convId: String, convSeq: Long): MessageEntity? =
    messages.byConvSeq(owner, convId, convSeq)

/** 在途未确认的消息——重连后按同一 `client_msg_id` 重发。 */
suspend fun MessageRepository.inFlight(owner: String): List<PendingMessageEntity> = pending.inFlight(owner)

/**
 * 按 `client_msg_id` 查一条待发行，**不按状态过滤**（[inFlight] 只挑 Sending 的，
 * 找不到 Failed 行——手动点红❗重试要找的恰恰是 Failed 那一条，见 [MessageService.resend]）。
 */
suspend fun MessageRepository.pendingByClientId(owner: String, cid: String): PendingMessageEntity? =
    pending.byClientId(owner, cid)

/** 被用户取消发送的 client_msg_id（进程内；cid 是 UUID，不会误伤别的）。 */
internal val cancelledSends: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/**
 * ack 到达：落真身、清待发、bump 会话。
 *
 * **正文要从 pending 里取**——ack 只回 id/seq/时间戳，不回带正文（PROTOCOL §4.2）。
 * pending 已被清掉（重复 ack / 进程重启后重发）时只能落一条没正文的骨架，
 * 靠后续 sync 用同一 conv_seq 幂等补全。
 */
suspend fun MessageRepository.onAck(owner: String, ack: AckData) {
    val cached = pending.byClientId(owner, ack.clientMsgId)
    // 用户取消了这条（帧已发出、ack 在路上）：不落骨架行——那会是一个空白文本气泡 + 错的会话预览，等 sync 补真身
    if (cached == null && cancelledSends.remove(ack.clientMsgId)) {
        log.i("msg_ack_after_cancel", "cid" to ack.clientMsgId, "seq" to ack.convSeq)
        return
    }
    if (cached == null) {
        log.w("msg_ack_without_pending", "cid" to ack.clientMsgId, "seq" to ack.convSeq)
    }
    // ack 只回带身份与序号；其余随消息走的字段一律从待发行补
    // （漏一个就是「只在自己这侧坏」的那类 bug，已经踩过三次，见 AckCarryOver）
    val row = AckCarryOver.enrich(
        MessageEntity(
            ownerUid = owner,
            convId = ack.convId,
            convSeq = ack.convSeq,
            serverMsgId = ack.serverMsgId,
            clientMsgId = ack.clientMsgId,
            sender = owner,
            contentType = ContentType.TEXT,
            timestamp = ack.timestamp,
        ),
        cached,
    )
    // 自己发的这一条也是「服务端给过了、本地有了」的一格：不登记，对端紧接着的下一条就成了孤岛
    tx.run {
        messages.upsert(row)
        if (ack.convSeq > 0) ranges.register(owner, ack.convId, ack.convSeq, ack.convSeq)
        advanceSyncedIfNext(owner, ack.convId, ack.convSeq)
    }
    pending.remove(owner, ack.clientMsgId)
    bumpConversation(owner, ack.convId, row)
    log.i("msg_acked", "convId" to ack.convId, "seq" to ack.convSeq, "cid" to ack.clientMsgId)
}

/**
 * 服务端拒绝了某条发送（PROTOCOL §8：error 带 client_msg_id）。
 * 常见码：200102 被拉黑 / 200103 非好友 / 300004 被禁言 / 300203 不是群成员。
 */
suspend fun MessageRepository.onSendRejected(owner: String, err: ErrorData) {
    val cid = err.clientMsgId ?: return
    // 通话记录被拉黑（200102）：主叫端**吞掉**，只写日志——不留红色「未发送」，更不弹提示（设计 §1）。
    if (cid.startsWith(CALL_CID_PREFIX) && err.code == ErrCode.FRIEND_BLOCKED) {
        pending.remove(owner, cid)
        log.w("msg_call_record_blocked", "cid" to cid)
        return
    }
    pending.markState(owner, cid, SendState.Failed.name, err.code)
    log.w("msg_send_rejected", "cid" to cid, "code" to err.code)
}
