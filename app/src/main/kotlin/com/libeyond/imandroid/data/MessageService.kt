package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ConversationsApi
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrorData
import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.MessageData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.ReceiptData
import com.libeyond.imandroid.sdk.protocol.SendMsgData
import com.libeyond.imandroid.sdk.protocol.SyncCursorItem
import com.libeyond.imandroid.sdk.protocol.SyncReqData
import com.libeyond.imandroid.sdk.protocol.SyncRespData
import com.libeyond.imandroid.sdk.ws.IMSocketManager
import kotlinx.coroutines.CoroutineScope
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
    private val conversationsApi: ConversationsApi,
    /** 当前账号；未登录为 null。切账号时必须换掉，否则新账号会写进旧账号的行。 */
    private val ownerProvider: () -> String?,
) {
    private val log = IMLog.tag("IM.Msg")

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

    /** 重发（红❗点击 / 重连后补发）。**沿用同一个 client_msg_id**，服务端幂等去重。 */
    suspend fun resend(clientMsgId: String) {
        val owner = ownerProvider() ?: return
        val p = repo.inFlight(owner).firstOrNull { it.clientMsgId == clientMsgId } ?: return
        transmit(p.clientMsgId, p.convId, p.to, p.contentType, p.content, p.replyToConvSeq)
    }

    private fun transmit(
        clientMsgId: String,
        convId: String,
        to: String,
        contentType: String,
        content: String,
        replyToConvSeq: Long?,
    ) {
        val payload = ProtocolJson.encodeToJsonElement(
            SendMsgData.serializer(),
            SendMsgData(
                clientMsgId = clientMsgId,
                convId = convId,
                to = to,
                contentType = contentType,
                content = content,
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

    fun sendReceipt(convId: String, status: String, upTo: Long) {
        socket.send(
            FrameType.RECEIPT,
            ProtocolJson.encodeToJsonElement(
                ReceiptData.serializer(),
                ReceiptData(convId = convId, status = status, upToConvSeq = upTo),
            ),
        )
    }

    /** 可见即读：把已读位点推到 upTo 并上报。 */
    suspend fun markRead(convId: String, upTo: Long) {
        val owner = ownerProvider() ?: return
        repo.markRead(owner, convId, upTo)
        sendReceipt(convId, ReceiptData.READ, upTo)
    }

    // ————————————————— 连接与同步 —————————————————

    private suspend fun onConnected() {
        val owner = ownerProvider() ?: return
        refreshConversations()
        requestSync(owner)
        resendInFlight(owner)
    }

    /** 拉会话列表（权威快照）。 */
    suspend fun refreshConversations() {
        val owner = ownerProvider() ?: return
        try {
            repo.applyConversationList(owner, conversationsApi.list())
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
        log.i("resend_in_flight", "count" to list.size)
        list.forEach { transmit(it.clientMsgId, it.convId, it.to, it.contentType, it.content, it.replyToConvSeq) }
    }
}
