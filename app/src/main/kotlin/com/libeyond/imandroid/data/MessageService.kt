package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ConversationsApi
import com.libeyond.imandroid.sdk.api.UploadApi
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrorData
import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.MessageData
import com.libeyond.imandroid.sdk.protocol.MsgHiddenData
import com.libeyond.imandroid.sdk.protocol.MsgOpData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.ReceiptData
import com.libeyond.imandroid.sdk.protocol.SendMsgData
import com.libeyond.imandroid.sdk.protocol.SyncCursorItem
import com.libeyond.imandroid.sdk.protocol.SyncReqData
import com.libeyond.imandroid.sdk.protocol.TypingData
import com.libeyond.imandroid.sdk.protocol.WatchData
import com.libeyond.imandroid.sdk.protocol.PresenceFrame
import com.libeyond.imandroid.sdk.protocol.SyncRespData
import com.libeyond.imandroid.sdk.ws.IMSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * 帧分派 + 同步编排（对应 iOS 的 `IMMessageService`）。
 *
 * 把 [IMSocketManager] 吐出的原始帧翻译成 [MessageRepository] 的写入动作。
 * **协议知识集中在这里**，仓库只管落库，UI 只管读库。
 */
class MessageService(
    private val scope: CoroutineScope,
    private val socket: IMSocketManager,
    private val repo: MessageRepository,
    val presence: PresenceStore,
    private val conversationsApi: ConversationsApi,
    private val upload: UploadApi,
    /** 当前账号；未登录为 null。切账号时必须换掉，否则新账号会写进旧账号的行。 */
    private val ownerProvider: () -> String?,
) {
    private val log = IMLog.tag("IM.Msg")

    private val _friendEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** 好友关系有变（收到 friend 帧）。UI 据此重拉 /friends。 */
    val friendEvents: SharedFlow<Unit> = _friendEvents.asSharedFlow()

    fun start() {
        scope.launch {
            socket.frames.collect { env ->
                val owner = ownerProvider() ?: return@collect
                try {
                    dispatch(owner, env.type, env.data)
                } catch (e: Exception) {
                    // 一帧处理失败不能掀翻整条收集流——否则之后所有消息都收不到。
                    // 带上异常消息：只记类名等于把线索扔了——2026-09-07 排 sync_resp
                    // 解析失败时就因为只有 "JsonDecodingException" 而多花了一轮。
                    log.w(
                        "frame_dispatch_failed",
                        "type" to env.type,
                        "err" to e.javaClass.simpleName,
                        "msg" to (e.message?.take(300) ?: ""),
                    )
                }
            }
        }
        socket.onConnected = { scope.launch { onConnected() } }
    }

    private suspend fun dispatch(owner: String, type: String, data: JsonElement?) {
        when (type) {
            FrameType.ACK -> data?.let {
                repo.onAck(owner, ProtocolJson.decodeFromJsonElement(AckData.serializer(), it))
            }

            FrameType.NEW_MSG -> data?.let {
                val m = ProtocolJson.decodeFromJsonElement(MessageData.serializer(), it)
                repo.onIncoming(owner, m, bumpUnread = true)
                // §4.3 末：接收方收到后**必须回 receipt(delivered)**
                if (m.from != owner) sendReceipt(m.convId, ReceiptData.DELIVERED, m.convSeq)
            }

            FrameType.SYNC_RESP -> data?.let {
                applySync(owner, ProtocolJson.decodeFromJsonElement(SyncRespData.serializer(), it))
            }

            FrameType.RECEIPT -> data?.let {
                repo.applyPeerReceipt(owner, ProtocolJson.decodeFromJsonElement(ReceiptData.serializer(), it))
            }

            FrameType.PRESENCE -> data?.let { el ->
                val d = ProtocolJson.decodeFromJsonElement(PresenceFrame.serializer(), el)
                presence.applyPresence(
                    d.user,
                    PresenceSnapshot(d.status, d.onlineUntil, d.lastSeen),
                )
            }

            FrameType.TYPING -> data?.let { el ->
                val d = ProtocolJson.decodeFromJsonElement(TypingData.serializer(), el)
                val from = d.from
                // from 为空、或就是自己，都不显示（本人回声）
                if (!from.isNullOrEmpty() && from != owner) presence.onTyping(d.convId, from)
            }

            FrameType.MSG_OP -> data?.let { el ->
                repo.applyMsgOp(owner, ProtocolJson.decodeFromJsonElement(MsgOpData.serializer(), el))
            }

            FrameType.CONV_UPDATE -> data?.let { el ->
                repo.applyConvUpdate(owner, ProtocolJson.decodeFromJsonElement(ConvUpdateData.serializer(), el))
            }

            FrameType.MSG_HIDDEN -> data?.let { el ->
                val d = ProtocolJson.decodeFromJsonElement(MsgHiddenData.serializer(), el)
                repo.applyMsgHidden(owner, d.convId, d.convSeq)
            }

            // 收到任意 friend 帧即重拉列表，event 只作语义/日志（PROTOCOL §6.5）
            FrameType.FRIEND -> {
                log.i("friend_event")
                _friendEvents.tryEmit(Unit)
            }

            FrameType.ERROR -> data?.let {
                val e = ProtocolJson.decodeFromJsonElement(ErrorData.serializer(), it)
                if (e.clientMsgId != null) {
                    // 带 client_msg_id = 对某条发送的拒绝，把那条标失败
                    repo.onSendRejected(owner, e)
                } else {
                    log.w("ws_error_frame", "code" to e.code)
                }
            }

            // 收到未知 type 必须忽略、不崩（PROTOCOL §2）
            else -> log.d("frame_ignored", "type" to type)
        }
    }

    // ————————————————— 发送 —————————————————

    /** 发一条文本。先落库再发帧，杀进程也不会凭空消失。 */
    suspend fun sendText(convId: String, to: String, text: String, replyToConvSeq: Long? = null) {
        val owner = ownerProvider() ?: return
        val p = repo.createPending(owner, convId, to, text, ContentType.TEXT, replyToConvSeq)
        transmit(p.clientMsgId, convId, to, ContentType.TEXT, text, replyToConvSeq)
    }

    /**
     * 发一条媒体消息（图片/视频/文件/语音）。
     *
     * **先落一条待发消息再上传**：上传可能几十秒，这期间用户得看得见「发送中」，
     * 而且杀进程后那张图不能凭空消失。iOS 有一类长期欠账正是「粘贴图/相机拍照
     * 那两条路的失败气泡不落库，重进会话就没了」，本端不重蹈。
     *
     * 上传失败 → 标 Failed（红❗可重试）；成功 → 把本地 uri 换成服务端 url 再发帧。
     */
    suspend fun sendMedia(
        convId: String,
        to: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        contentType: String,
        caption: String? = null,
        localPreviewUri: String = "",
    ) {
        val owner = ownerProvider() ?: return
        val p = repo.createPending(
            owner = owner, convId = convId, to = to,
            content = localPreviewUri, contentType = contentType,
        )
        val r = try {
            upload.upload(bytes, fileName, mimeType, asVoice = contentType == ContentType.VOICE)
        } catch (e: com.libeyond.imandroid.sdk.http.ApiException) {
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(
                    code = e.code, message = e.message, clientMsgId = p.clientMsgId,
                ),
            )
            log.w("media_upload_failed", "cid" to p.clientMsgId, "code" to e.code)
            return
        }
        repo.updatePendingContent(owner, p.clientMsgId, r.url, r.size)
        transmit(p.clientMsgId, convId, to, contentType, r.url, null, fileName, r.size, caption)
    }

    /** 重发（红❗点击 / 重连后补发）。**沿用同一个 client_msg_id**，服务端幂等去重。 */
    suspend fun resend(clientMsgId: String) {
        val owner = ownerProvider() ?: return
        val p = repo.inFlight(owner).firstOrNull { it.clientMsgId == clientMsgId } ?: return
        transmit(
            p.clientMsgId, p.convId, p.to, p.contentType, p.content, p.replyToConvSeq,
            p.fileName, p.fileSize, p.caption,
        )
    }

    private fun transmit(
        clientMsgId: String,
        convId: String,
        to: String,
        contentType: String,
        content: String,
        replyToConvSeq: Long?,
        fileName: String? = null,
        fileSize: Long? = null,
        caption: String? = null,
    ) {
        val payload = ProtocolJson.encodeToJsonElement(
            SendMsgData.serializer(),
            SendMsgData(
                clientMsgId = clientMsgId,
                convId = convId,
                to = to,
                contentType = contentType,
                content = content,
                fileName = fileName,
                fileSize = fileSize,
                caption = caption,
                replyToConvSeq = replyToConvSeq,
            ),
        )
        val sent = socket.send(FrameType.SEND_MSG, payload)
        if (!sent) {
            // 没发出去不标失败——它仍是「发送中」，等重连后由 resendInFlight 补发。
            // 标失败会让用户看到红❗然后连接一恢复消息又自己发出去了，很怪。
            log.i("msg_send_deferred_offline", "cid" to clientMsgId)
        }
    }

    /**
     * 上报「正在输入」。上行只带 conv_id，服务端中继时附 from。
     * 节流由调用方负责——每次按键都发是错的。
     */
    fun sendTyping(convId: String) {
        socket.send(
            FrameType.TYPING,
            ProtocolJson.encodeToJsonElement(TypingData.serializer(), TypingData(convId = convId)),
        )
    }

    /**
     * 上报当前要显示在线态的 uid 全集（**全量替换语义**）。
     *
     * 服务端对每次 watch（含集合不变的重发）都回快照，故进入界面与重连后都要发一次，
     * 别因为集合没变就跳过——那正是「返回聊天页在线态不刷新」的成因。
     */
    fun sendWatch(set: Set<String>, force: Boolean = false) {
        if (!presence.updateWatch(set, force)) return
        socket.send(
            FrameType.WATCH,
            ProtocolJson.encodeToJsonElement(
                WatchData.serializer(), WatchData(presence.currentWatchSet()),
            ),
        )
    }

    /**
     * 发一条消息操作（§6.7）。`client_msg_id` 是幂等键，重发命中不重复应用。
     *
     * **不做本地乐观更新**：撤回/删除有服务端权限与时间窗判定（超窗回 300008、
     * 无权 300006），先在本地删掉再被服务端拒绝，就得把消息变回来——
     * 那比等一下服务端广播回来难看得多。服务端会把 msg_op 广播给**含发起方在内**的
     * 全体成员设备，本端照常从帧里收敛。
     */
    fun sendMsgOp(
        convId: String,
        op: String,
        targetConvSeq: Long,
        content: String? = null,
        pinned: Boolean? = null,
    ) {
        socket.send(
            FrameType.MSG_OP,
            ProtocolJson.encodeToJsonElement(
                MsgOpData.serializer(),
                MsgOpData(
                    op = op,
                    convId = convId,
                    targetConvSeq = targetConvSeq,
                    clientMsgId = java.util.UUID.randomUUID().toString(),
                    content = content,
                    pinned = pinned,
                ),
            ),
        )
        log.i("msg_op_sent", "op" to op, "convId" to convId, "target" to targetConvSeq)
    }

    fun sendReceipt(convId: String, status: String, upTo: Long) {
        socket.send(
            FrameType.RECEIPT,
            ProtocolJson.encodeToJsonElement(
                ReceiptData.serializer(),
                ReceiptData(convId = convId, status = status, upToConvSeq = upTo),
            ),
        )
    }

    /** 每个会话上一次真正上报过的已读位点，用于抑制重复回执。 */
    private val lastReportedRead = mutableMapOf<String, Long>()

    /**
     * 可见即读：把已读位点推到 [upTo] 并上报。
     *
     * **位点没前进就什么都不做**。可见即读会随每次滚动/列表变化触发，
     * 不做这道闸的话：① 往回翻历史时会发一个**倒退**的 read 回执；
     * ② 静止不动也会因重组反复发同一帧，白白刷服务端。
     */
    suspend fun markRead(convId: String, upTo: Long) {
        val owner = ownerProvider() ?: return
        val last = lastReportedRead[convId] ?: 0
        if (upTo <= last) return
        lastReportedRead[convId] = upTo
        repo.markRead(owner, convId, upTo)
        sendReceipt(convId, ReceiptData.READ, upTo)
    }

    // ————————————————— 连接与同步 —————————————————

    private suspend fun onConnected() {
        val owner = ownerProvider() ?: return
        refreshConversations()
        requestSync(owner)
        resendInFlight(owner)
        // watch 订阅是**连接级易失态**，断连即清 → 重连必须重发当前集合，
        // 否则重连后所有在线态就此冻在旧值上（PROTOCOL §5.5「生命周期」）。
        sendWatch(presence.currentWatchSet().toSet(), force = true)
    }

    /** 拉会话列表（权威快照）。 */
    suspend fun refreshConversations() {
        val owner = ownerProvider() ?: return
        try {
            repo.applyConversationList(owner, conversationsApi.list(), presence)
        } catch (e: Exception) {
            log.w("conversations_refresh_failed", "err" to e.javaClass.simpleName)
        }
    }

    private suspend fun requestSync(owner: String) {
        val cursors = repo.syncCursors(owner).map { (convId, seq) -> SyncCursorItem(convId, seq) }
        if (cursors.isEmpty()) return
        socket.send(
            FrameType.SYNC_REQ,
            ProtocolJson.encodeToJsonElement(SyncReqData.serializer(), SyncReqData(cursors)),
        )
        log.i("sync_requested", "conversations" to cursors.size)
    }

    /**
     * 应用一页同步结果。
     *
     * 游标推进严格走 [SyncCursorRule]：**先把本页按序落库，成功了才推进到 covered**。
     * `has_more` 时以**新游标**续拉，不是以 latest。
     */
    private suspend fun applySync(owner: String, resp: SyncRespData) {
        var needMore = false
        val nextCursors = mutableListOf<SyncCursorItem>()
        for (c in resp.conversations) {
            val firstFailed = repo.onIncomingBatch(owner, c.messages)
            repo.advanceCursor(owner, c.convId, c.coveredConvSeq, firstFailed)
            log.i(
                "sync_page_applied",
                "convId" to c.convId, "msgs" to c.messages.size,
                "covered" to c.coveredConvSeq, "hasMore" to c.hasMore,
            )
            if (c.hasMore && firstFailed == null) {
                needMore = true
                nextCursors += SyncCursorItem(c.convId, SyncCursorRule.nextSince(c.coveredConvSeq))
            }
        }
        if (needMore) {
            socket.send(
                FrameType.SYNC_REQ,
                ProtocolJson.encodeToJsonElement(SyncReqData.serializer(), SyncReqData(nextCursors)),
            )
        }
        // 同步完刷一次会话列表，未读数以服务端为准
        refreshConversations()
    }

    /** 重连后把在途未确认的消息按同一 client_msg_id 重发。 */
    private suspend fun resendInFlight(owner: String) {
        val list = repo.inFlight(owner)
        if (list.isEmpty()) return

        // **正文还是本地 uri 的媒体消息不能重发**：那是「上传没走完就被杀进程/断线」的残留。
        // 原样发出去，服务端会把 `content://media/...` 当消息正文存下来，
        // 收件人拿到一个**永远打不开的地址**——而且这条错误消息再也改不回来了。
        // 字节已经不在内存里（Uri 的读权限也随进程没了），重发无从谈起，
        // 只能标失败让用户重选一次。
        val (resendable, stale) = list.partition { !isLocalUri(it.content) }
        stale.forEach {
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(
                    code = com.libeyond.imandroid.sdk.protocol.ErrCode.PARAM_INVALID,
                    message = "上传未完成，请重新发送",
                    clientMsgId = it.clientMsgId,
                ),
            )
        }
        if (stale.isNotEmpty()) log.w("resend_dropped_unuploaded", "count" to stale.size)

        if (resendable.isEmpty()) return
        log.i("resend_in_flight", "count" to resendable.size)
        resendable.forEach {
            transmit(
                it.clientMsgId, it.convId, it.to, it.contentType, it.content, it.replyToConvSeq,
                it.fileName, it.fileSize, it.caption,
            )
        }
    }
}


/**
 * 判断一条待发消息的正文是不是**本地** uri（还没上传完）。
 *
 * 抽成顶层纯函数便于单测——这条判据错了不会报错，只会让收件人收到一个
 * 打不开的 `content://` 地址，而且再也改不回来。
 */
internal fun isLocalUri(content: String): Boolean =
    content.startsWith("content://") || content.startsWith("file://")
