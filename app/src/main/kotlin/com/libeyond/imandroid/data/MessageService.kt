package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.ConversationsApi
import com.libeyond.imandroid.sdk.api.UploadApi
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrCode
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
import com.libeyond.imandroid.sdk.protocol.TypingData
import com.libeyond.imandroid.sdk.protocol.VoiceTranscriptData
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
    /**
     * 仓库。与 [ownerProvider] / [media] / [transmit] 一样是 `internal` 而不是 `private`，
     * 为的是让 `MessageSync.kt` 够得着（连接后的同步编排那一组，搬出去只为控体量，调用形态一个字没改）。
     *
     * ⚠️ **别把这理解成"只有同包能碰"**：Kotlin 的 `internal` 是**模块级**，没有 Java 那种 package-private，
     * 而 `:app` 统共一个模块、`IMClient.messages` 又是公开的——整个 `ui` 包照样写得出
     * `client.messages.repo` / `.transmit(...)`。**编译器守不住这条线，只能靠约定**：
     * 约定是「除 `MessageSync.kt` 外谁都别碰」，因为协议知识集中在本类，绕过去写库就没人执行
     * [MessageRepository] 入库边界上那套口径了。review 时真正该打回的信号就是
     * `ui` 里冒出 `client.messages.repo` / `.transmit(` / `.media`。
     */
    internal val repo: MessageRepository,
    val presence: PresenceStore,
    private val conversationsApi: ConversationsApi,
    private val upload: UploadApi,
    /** 已下载媒体的落盘。自己发出去的字节直接放进它，免得发完再下回来一遍。 */
    private val mediaCache: MediaCache,
    /** 当前账号；未登录为 null。切账号时必须换掉，否则新账号会写进旧账号的行。 */
    internal val ownerProvider: () -> String?,
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

    private val _voiceTranscripts = MutableSharedFlow<VoiceTranscriptData>(extraBufferCapacity = 8)
    /** 收到 `voice_transcript` 帧（§6.10）。只推给请求者本人，见 [com.libeyond.imandroid.voice.VoiceTranscriber]。 */
    val voiceTranscripts: SharedFlow<VoiceTranscriptData> = _voiceTranscripts.asSharedFlow()

    private val _capabilityUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    /**
     * 账号级能力有变（收到 capabilities_update），值是服务端的新版本号（PROTOCOL §6.9）。
     * 这里只转发，**去重与重拉在 [DownloadSettingsStore]**——只有它知道已经采纳到哪个版本。
     */
    val capabilityUpdates: SharedFlow<Long> = _capabilityUpdates.asSharedFlow()

    private val _notifySettingsUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    /**
     * 账号级通知设置有变（收到 `notify_settings_update`，PROTOCOL §6.13），值是服务端的新版本号。
     * 这里只转发，**去重与重拉在 [AccountNotifySettingsStore]**——与 [capabilityUpdates] 是两条独立的版本序列。
     */
    val notifySettingsUpdates: SharedFlow<Long> = _notifySettingsUpdates.asSharedFlow()

    private val _listedConversations = kotlinx.coroutines.flow.MutableStateFlow<ListedConversations?>(null)
    /**
     * 本次进程里最近一次**成功**拉到的会话列表（哪个账号、几条）。会话列表的空态判据要它
     * （[ConversationListPhase]：本地空 ≠ 没有会话，服务端也说没有才是）。失败不写——失败时它说不出结论。
     */
    val listedConversations: kotlinx.coroutines.flow.StateFlow<ListedConversations?> = _listedConversations

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
                if (m.from != owner) {
                    sendReceipt(m.convId, ReceiptData.DELIVERED, m.convSeq)
                    // 通知与提示音（NOTIFICATIONS_DESIGN §3.1）：只有这一条实时路径会调判定，
                    // sync/window 补拉都不经过这里——见 IncomingAlert 类注释。
                    IncomingAlert.handle(owner, m, repo)
                }
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

            FrameType.VOICE_TRANSCRIPT -> data?.let { el ->
                _voiceTranscripts.tryEmit(ProtocolJson.decodeFromJsonElement(VoiceTranscriptData.serializer(), el))
            }

            // 收到任意 friend 帧即重拉列表，event 只作语义/日志（PROTOCOL §6.5）
            FrameType.FRIEND -> {
                log.i("friend_event")
                _friendEvents.tryEmit(Unit)
            }

            // 账号级能力有变（PROTOCOL §6.9）。这里只转发版本号，去重与重拉在 DownloadSettingsStore
            FrameType.CAPABILITIES_UPDATE -> data?.let {
                val d = ProtocolJson.decodeFromJsonElement(
                    com.libeyond.imandroid.sdk.protocol.CapabilitiesUpdateData.serializer(), it,
                )
                log.i("capabilities_update", "version" to d.version)
                _capabilityUpdates.tryEmit(d.version)
            }

            // 账号级通知设置有变（PROTOCOL §6.13）。这里只转发版本号，去重与重拉在 AccountNotifySettingsStore
            FrameType.NOTIFY_SETTINGS_UPDATE -> data?.let {
                val d = ProtocolJson.decodeFromJsonElement(
                    com.libeyond.imandroid.sdk.protocol.NotifySettingsUpdateData.serializer(), it,
                )
                log.i("notify_settings_update", "version" to d.version)
                _notifySettingsUpdates.tryEmit(d.version)
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
     * 发一条通话记录（`call`）。**`client_msg_id` 固定为 `call-<call_id>`**：主叫两台设备都收到结束事件、
     * 断线重发，服务端都凭它去重只落一条（设计 §2）。发送失败不影响通话，只留待发行 / 日志。
     */
    suspend fun sendCallRecord(convId: String, to: String, callId: String, json: String) {
        val owner = ownerProvider() ?: return
        val p = repo.createPending(owner, convId, to, json, ContentType.CALL, clientMsgId = CALL_CID_PREFIX + callId)
        transmit(p.clientMsgId, convId, to, ContentType.CALL, json, null)
    }

    /**
     * 转发一条消息到另一个会话（M4-3，PROTOCOL §4.3 `forward_from`）。
     *
     * **不是"复制文本再发一遍"**：要带上 `forward_from`，收端才显示「转发自 X」。
     * 溯源名由 [Forward.originOf] 算——见那里的两条纪律（公开名 / 转发链保留最初作者）。
     *
     * 媒体消息**直接复用原 URL**，不重新上传：服务端存的是同一份文件，
     * 再传一遍既慢又白占空间（iOS/Web 同口径）。
     *
     * @param groupId 相册整体转发时的**新**分组 ID（见 `SelectionActions.regroupAlbums`）；
     *   不能沿用原 ID——原相册里没选的那几张不在新会话里，收端会按原 ID 等一组永远凑不齐的图。
     */
    suspend fun forward(msg: MessageEntity, toConvId: String, to: String, origin: String, groupId: String? = null) {
        val owner = ownerProvider() ?: return
        // 封面/尺寸/时长必须跟着转发走，判据与理由在 [Forward.attributesOf]：
        // 漏带是**静默**的——收端只能按「像素未知」渲染（16:9 的视频变成方块、没有封面），
        // 且这几个字段随消息落库，事后补不回来。iOS `forwardEchoContent:` 与 im-web
        // `useForward.ts` 一直带着，本端此前是这条对称链上唯一没跟的一端。
        val attrs = Forward.attributesOf(msg)
        // 图说里的 @ 提及随转发跟随（图片/视频才有，见 Forward.attributesOf）——
        // 待发行存 JSON（重发要原样重发谁被 @ 了，重名成员没法从片段反推），发帧用类型化列表。
        val mentionSpansJson = Mention.encodeSpans(attrs.mentionSpans.orEmpty())
        val mentionsJson = Mention.encodeMentions(attrs.mentions.orEmpty())
        val p = repo.createPending(
            owner = owner, convId = toConvId, to = to,
            content = msg.content, contentType = msg.contentType,
            forwardFrom = origin, groupId = groupId,
            // **待发行也要有**：`resend` 是从这一行读字段的，行里没有就等于"重发一次元数据就没了"
            // （createPending 的 KDoc 记着这个坑已经第四次了）；待发气泡的排版也读它。
            fileName = msg.fileName, fileSize = msg.fileSize, caption = msg.caption,
            mediaW = attrs.mediaW, mediaH = attrs.mediaH, duration = attrs.duration,
            poster = attrs.poster, thumb = attrs.thumb, waveform = attrs.waveform,
            mentionSpans = mentionSpansJson, mentions = mentionsJson,
        )
        transmit(
            p.clientMsgId, toConvId, to, msg.contentType, msg.content,
            replyToConvSeq = null,   // 引用不跟着转发走：被引用的那条不在新会话里
            fileName = msg.fileName, fileSize = msg.fileSize, caption = msg.caption,
            forwardFrom = origin, groupId = groupId,
            mediaW = attrs.mediaW, mediaH = attrs.mediaH, duration = attrs.duration,
            poster = attrs.poster, thumb = attrs.thumb, waveform = attrs.waveform,
            // 不带 mentionAll：@所有人要目标群群主/管理员权限，转发不该在新会话里再次全员强提醒（同 iOS）
            mentions = attrs.mentions.orEmpty(), mentionSpans = attrs.mentionSpans.orEmpty(),
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
    internal val media = MediaSendPipeline(
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
        duration: Int? = null,
        waveform: String? = null,
    ): String? = media.createPendingRow(
        convId, to, contentType, localPreviewUri, fileName, fileSize, caption, groupId,
        duration, waveform,
    )

    /** 见 [MediaSendPipeline.attachThumb]。 */
    suspend fun attachMediaThumb(clientMsgId: String, thumb: String?) =
        media.attachThumb(clientMsgId, thumb)

    /** 见 [MediaSendPipeline.markFailed]。 */
    suspend fun markMediaFailed(clientMsgId: String, code: Int = 0, message: String = Str.s(R.string.net_error_file_read_failed)) =
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
        waveform: String? = null,
        pendingId: String? = null,
    ) = media.sendBytes(
        convId, to, bytes, fileName, mimeType, contentType, caption, localPreviewUri,
        groupId, mediaW, mediaH, duration, poster, thumb, waveform, pendingId,
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

    /**
     * 重发（红❗点击 / 重连后补发）。**沿用同一个 client_msg_id**，服务端幂等去重。
     *
     * 按 clientMsgId **不按状态**查（[MessageRepository.pendingByClientId]）——红❗点击要找的
     * 正是 Failed 行，[MessageRepository.inFlight] 只挑 Sending 的会找不到它，点了跟没点一样。
     */
    suspend fun resend(clientMsgId: String) {
        val owner = ownerProvider() ?: return
        val p = repo.pendingByClientId(owner, clientMsgId) ?: return
        if (isLocalUri(p.content)) {
            // 正文仍是本地 uri：上传没走完就失败的残留。语音落在应用私有目录，进程重启后
            // 依然读得到，能重新上传；图片/视频的本地 uri 是系统相册 content://，读权限随
            // 发起进程一起没了，**绝不能原样 transmit**——那会把 content:// 当正文发给对端，
            // 存进服务端后再也改不回来（同 MessageSync.resendInFlight 里 stale 分支的注释）。
            if (p.contentType == ContentType.VOICE) {
                media.reuploadVoice(p)
            } else {
                repo.onSendRejected(
                    owner,
                    ErrorData(
                        code = ErrCode.PARAM_INVALID,
                        message = Str.s(R.string.chat_resend_upload_incomplete),
                        clientMsgId = clientMsgId,
                    ),
                )
            }
            return
        }
        transmit(
            p.clientMsgId, p.convId, p.to, p.contentType, p.content, p.replyToConvSeq,
            p.fileName, p.fileSize, p.caption, p.forwardFrom, p.groupId,
            p.mediaW, p.mediaH, p.duration, p.poster, p.thumb, p.waveform,
            // @提及三件套按落库的片段**重新推导**，不另存 mentions/mentionAll：
            // 片段里已经含了每个 token 指向谁，空 uid 就是 @所有人——两份状态早晚会不一致
            mentions = Mention.parseMentions(p.mentions),
            mentionAll = Mention.mentionAllFromSpans(p.mentionSpans),
            mentionSpans = Mention.parseSpans(p.mentionSpans),
        )
    }

    internal fun transmit(
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
        /** 语音振幅指纹（仅 voice）。服务端对非 voice 与非法值静默丢弃，端上照传即可。 */
        waveform: String? = null,
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
                waveform = waveform,
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

    /** 进单聊信息页拉到的对端名片，并回会话行（信息页头部 / 会话列表随之更新）。 */
    suspend fun applyPeerCard(peerUid: String, card: com.libeyond.imandroid.sdk.api.UserCard) {
        val owner = ownerProvider() ?: return
        repo.applyPeerCard(owner, peerUid, card)
    }

    /** 拉会话列表（权威快照）。 */
    suspend fun refreshConversations() {
        val owner = ownerProvider() ?: return
        try {
            val list = conversationsApi.list()
            repo.applyConversationList(owner, list, presence)
            _listedConversations.value = ListedConversations(owner, list.size)
        } catch (e: Exception) {
            log.w("conversations_refresh_failed", "err" to e.javaClass.simpleName)
        }
    }
}
