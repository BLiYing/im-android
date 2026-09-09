package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ConversationsApi
import com.libeyond.imandroid.sdk.api.UploadApi
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MentionSpan
import com.libeyond.imandroid.sdk.protocol.ErrorData
import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.MessageData
import com.libeyond.imandroid.sdk.protocol.MsgHiddenData
import com.libeyond.imandroid.sdk.protocol.MsgOpData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.ReceiptData
import com.libeyond.imandroid.sdk.protocol.ReplyToData
import com.libeyond.imandroid.sdk.protocol.SendMsgData
import com.libeyond.imandroid.sdk.protocol.SyncCursorItem
import com.libeyond.imandroid.sdk.protocol.SyncReqData
import com.libeyond.imandroid.sdk.protocol.TypingData
import com.libeyond.imandroid.sdk.protocol.WatchData
import com.libeyond.imandroid.sdk.protocol.PresenceFrame
import com.libeyond.imandroid.sdk.protocol.SyncRespData
import com.libeyond.imandroid.sdk.protocol.WindowReqData
import com.libeyond.imandroid.sdk.protocol.WindowRespData
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
    internal val socket: IMSocketManager,
    private val repo: MessageRepository,
    val presence: PresenceStore,
    private val conversationsApi: ConversationsApi,
    private val upload: UploadApi,
    /** 已下载媒体的落盘。自己发出去的字节直接放进它，免得发完再下回来一遍。 */
    private val mediaCache: MediaCache,
    /** 当前账号；未登录为 null。切账号时必须换掉，否则新账号会写进旧账号的行。 */
    private val ownerProvider: () -> String?,
) {
    /** 「按锚点开窗」的一问一答（MESSAGE_WINDOW_DESIGN §3.2），实现在 [WindowRequester]。 */
    internal val windows = WindowRequester(socket, scope)

    internal val log = IMLog.tag("IM.Msg")

    /**
     * 分片上传进度（clientMsgId → 百分比），UI 直接 collect。
     * 只有 [sendMediaStream] 这条路会写它——图片走整包上传，几百 KB，接了只会闪。
     */
    val uploadProgress = UploadProgress()

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

            // 按锚点开窗（MESSAGE_WINDOW_DESIGN §3.2）。**只落库、不推进同步游标**——
            // 窗口取数是一次性快照，推进游标会让 sync 以为这一段已经覆盖过了。
            FrameType.WINDOW_RESP -> data?.let {
                val resp = ProtocolJson.decodeFromJsonElement(WindowRespData.serializer(), it)
                repo.onIncomingBatch(owner, resp.messages)
                windows.deliver(resp)
                log.i(
                    "window_applied", "convId" to resp.convId, "msgs" to resp.messages.size,
                    "anchorFound" to resp.anchorFound,
                )
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

    /**
     * 发一条文本。先落库再发帧，杀进程也不会凭空消失。
     *
     * [mentions] / [mentionAll] / [mentionSpans] 是群 @提及（PROTOCOL §4.1，**仅群聊**；
     * 单聊带上会被服务端忽略）。三者由输入栏在**发送那一刻**按文本复核算出
     * （`data/Mention.kt` 的 resolveMentions / resolveMentionAll / resolveSpans），
     * 不是记着"用户点过谁"就发谁——用户手动删掉 token 就该自动不再 @ 他。
     */
    suspend fun sendText(
        convId: String,
        to: String,
        text: String,
        replyToConvSeq: Long? = null,
        mentions: List<String> = emptyList(),
        mentionAll: Boolean = false,
        mentionSpans: List<MentionSpan> = emptyList(),
    ) {
        val owner = ownerProvider() ?: return
        val p = repo.createPending(
            owner, convId, to, text, ContentType.TEXT, replyToConvSeq,
            mentionSpans = Mention.encodeSpans(mentionSpans),
            mentions = Mention.encodeMentions(mentions),
        )
        transmit(
            p.clientMsgId, convId, to, ContentType.TEXT, text, replyToConvSeq,
            mentions = mentions, mentionAll = mentionAll, mentionSpans = mentionSpans,
        )
    }

    /**
     * 发一条卡片消息（`contact` 个人名片 / `chat_record` 合并转发）。
     *
     * 与 [sendText] 只差 `contentType`——**内容就是那段 JSON 字符串**，服务端只透传。
     * 单独开一个方法而不是给 sendText 加参数：卡片的 `content` 不是给人读的文本，
     * 混在一起早晚会有人给它接引用/@提及那套文本逻辑。
     */
    suspend fun sendCard(convId: String, to: String, contentType: String, json: String) {
        val owner = ownerProvider() ?: return
        val p = repo.createPending(owner, convId, to, json, contentType)
        transmit(p.clientMsgId, convId, to, contentType, json, null)
    }

    /**
     * 转发一条消息到另一个会话（M4-3，PROTOCOL §4.3 `forward_from`）。
     *
     * **不是"复制文本再发一遍"**：要带上 `forward_from`，收端才显示「转发自 X」。
     * 溯源名由 [Forward.originOf] 算——见那里的两条纪律（公开名 / 转发链保留最初作者）。
     *
     * 媒体消息**直接复用原 URL**，不重新上传：服务端存的是同一份文件，
     * 再传一遍既慢又白占空间（iOS/Web 同口径）。
     */
    suspend fun forward(msg: MessageEntity, toConvId: String, to: String, origin: String) {
        val owner = ownerProvider() ?: return
        val p = repo.createPending(
            owner = owner, convId = toConvId, to = to,
            content = msg.content, contentType = msg.contentType,
            forwardFrom = origin,
        )
        transmit(
            p.clientMsgId, toConvId, to, msg.contentType, msg.content,
            replyToConvSeq = null,   // 引用不跟着转发走：被引用的那条不在新会话里
            fileName = msg.fileName, fileSize = msg.fileSize, caption = msg.caption,
            forwardFrom = origin,
        )
        log.i("msg_forwarded", "from" to msg.convId, "to" to toConvId, "seq" to msg.convSeq)
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
    // —— 媒体发送（落待发行 / 上传 / 回写 / 发帧）拆到 MediaSendPipeline.kt ——
    // 拆的理由是体量门禁（本文件 626 > 600），拆的**边界**是「一条媒体从选中到发出去」
    // 这条完整链路：它自成一体，且四个入口（图片/视频/重发/失败标记）共享同一套状态机——
    // 状态机分叉过一次就会出现「视频发失败了但没有红❗」这种查不出来的事。
    private val media = MediaSendPipeline(
        repo = repo,
        cache = mediaCache,
        upload = upload,
        uploadProgress = uploadProgress,
        ownerProvider = ownerProvider,
        transmit = ::transmit,
        log = log,
    )

    /** 见 [MediaSendPipeline.createPendingRow]。 */
    suspend fun createMediaPending(
        convId: String,
        to: String,
        contentType: String,
        localPreviewUri: String,
        fileName: String,
        fileSize: Long,
        caption: String? = null,
        groupId: String? = null,
    ): String? = media.createPendingRow(
        convId, to, contentType, localPreviewUri, fileName, fileSize, caption, groupId,
    )

    /** 见 [MediaSendPipeline.attachThumb]。 */
    suspend fun attachMediaThumb(clientMsgId: String, thumb: String?) =
        media.attachThumb(clientMsgId, thumb)

    /** 见 [MediaSendPipeline.markFailed]。 */
    suspend fun markMediaFailed(clientMsgId: String, code: Int = 0, message: String = "读取失败") =
        media.markFailed(clientMsgId, code, message)

    /** 见 [MediaSendPipeline.sendBytes]。 */
    suspend fun sendMedia(
        convId: String,
        to: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        contentType: String,
        caption: String? = null,
        localPreviewUri: String = "",
        groupId: String? = null,
        mediaW: Int? = null,
        mediaH: Int? = null,
        duration: Int? = null,
        poster: String? = null,
        thumb: String? = null,
        pendingId: String? = null,
    ) = media.sendBytes(
        convId, to, bytes, fileName, mimeType, contentType, caption, localPreviewUri,
        groupId, mediaW, mediaH, duration, poster, thumb, pendingId,
    )

    /** 见 [MediaSendPipeline.sendStream]。 */
    suspend fun sendMediaStream(
        convId: String,
        to: String,
        openStream: () -> java.io.InputStream?,
        totalBytes: Long,
        fileName: String,
        mimeType: String,
        contentType: String,
        caption: String? = null,
        localPreviewUri: String = "",
        groupId: String? = null,
        mediaW: Int? = null,
        mediaH: Int? = null,
        duration: Int? = null,
        poster: String? = null,
        thumb: String? = null,
        pendingId: String? = null,
    ) = media.sendStream(
        convId, to, openStream, totalBytes, fileName, mimeType, contentType, caption,
        localPreviewUri, groupId, mediaW, mediaH, duration, poster, thumb, pendingId,
    )

    /** 重发（红❗点击 / 重连后补发）。**沿用同一个 client_msg_id**，服务端幂等去重。 */
    suspend fun resend(clientMsgId: String) {
        val owner = ownerProvider() ?: return
        val p = repo.inFlight(owner).firstOrNull { it.clientMsgId == clientMsgId } ?: return
        transmit(
            p.clientMsgId, p.convId, p.to, p.contentType, p.content, p.replyToConvSeq,
            p.fileName, p.fileSize, p.caption, p.forwardFrom, p.groupId,
            p.mediaW, p.mediaH, p.duration, p.poster, p.thumb,
            // @提及三件套按落库的片段**重新推导**，不另存 mentions/mentionAll：
            // 片段里已经含了每个 token 指向谁，空 uid 就是 @所有人——两份状态早晚会不一致
            mentions = Mention.parseMentions(p.mentions),
            mentionAll = Mention.mentionAllFromSpans(p.mentionSpans),
            mentionSpans = Mention.parseSpans(p.mentionSpans),
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
        forwardFrom: String? = null,
        groupId: String? = null,
        mediaW: Int? = null,
        mediaH: Int? = null,
        duration: Int? = null,
        poster: String? = null,
        thumb: String? = null,
        mentions: List<String> = emptyList(),
        mentionAll: Boolean = false,
        mentionSpans: List<MentionSpan> = emptyList(),
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
                replyTo = replyToConvSeq?.takeIf { it > 0 }?.let { ReplyToData(it) },
                forwardFrom = forwardFrom,
                groupId = groupId,
                mediaW = mediaW,
                mediaH = mediaH,
                duration = duration,
                poster = poster,
                thumb = thumb,
                // 空表不传 null 之外的东西：服务端按 omitempty 读，传 [] 与不传等价但白占字节
                mentions = mentions.takeIf { it.isNotEmpty() },
                mentionAll = true.takeIf { mentionAll },
                mentionSpans = mentionSpans.takeIf { it.isNotEmpty() },
            ),
        )
        val sent = socket.send(FrameType.SEND_MSG, payload)
        if (!sent) {
            // 没发出去不标失败——它仍是「发送中」，等重连后由 resendInFlight 补发。
            // 标失败会让用户看到红❗然后连接一恢复消息又自己发出去了，很怪。
            log.i("msg_send_deferred_offline", "cid" to clientMsgId)
        }
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

    /** 按各会话的本地游标发一次 `sync_req`（增量补拉）。开窗取数走 [windows]，不是这一路。 */
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
        // **正在上传的那几条既不重发也不标失败**——它们的上传协程还在跑，
        // 标失败会让用户看到红❗，而几秒后它自己又发出去了（见 MediaSendPipeline.uploading）。
        val inProgress = list.filter { isLocalUri(it.content) && media.isUploading(it.clientMsgId) }
        val rest = list - inProgress.toSet()
        if (inProgress.isNotEmpty()) log.i("resend_skipped_uploading", "count" to inProgress.size)
        val (resendable, stale) = rest.partition { !isLocalUri(it.content) }
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
                it.fileName, it.fileSize, it.caption, it.forwardFrom, it.groupId,
                it.mediaW, it.mediaH, it.duration, it.poster, it.thumb,
                mentions = Mention.parseMentions(it.mentions),
                mentionAll = Mention.mentionAllFromSpans(it.mentionSpans),
                mentionSpans = Mention.parseSpans(it.mentionSpans),
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
