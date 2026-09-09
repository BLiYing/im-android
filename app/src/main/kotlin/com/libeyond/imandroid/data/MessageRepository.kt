package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationDao
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageDao
import com.libeyond.imandroid.sdk.protocol.SysSegment
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageDao
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.data.db.SendState
import com.libeyond.imandroid.sdk.api.ConversationSummary
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrorData
import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.MessageData
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.sdk.protocol.MsgOpData
import com.libeyond.imandroid.sdk.protocol.ReceiptData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 消息收发与落库。
 *
 * ## 发送链路（PROTOCOL §4）
 * ```
 * 本地落 pending(state=Sending) + 生成 client_msg_id
 *   → send_msg 上行
 *   → ack 回来：落 message 表（真实 conv_seq）+ 从 pending 移除
 *   → 或 error 带 client_msg_id：pending 标 Failed + 记业务码
 * ```
 * **超时重发用同一个 `client_msg_id`**，服务端靠它幂等去重，重发不会产生重复消息。
 *
 * ## 为什么先落库再发
 * 杀进程/断网时未发出的消息不能凭空消失。iOS 有一类长期欠账正是"发送中占位不落库"——
 * 粘贴图/相机拍照那两条路的失败气泡 content 为空不落库，重进会话就没了，
 * 后来加的「红❗点击重发」自然也覆盖不到它们。本端从第一版就全部落库。
 */
class MessageRepository(
    /**
     * DAO 是 `internal` 而不是 `private`：**只为让同包的查询扩展够得着**
     * （`MessageWindowQueries.kt`——窗口与搜索那一组读查询，从这里搬出去只是为了控体量，
     * 调用点仍写成 `repo.windowAround(...)`）。**除那个文件外别在包内直接碰它们**，
     * 写侧一律走本类的方法：入库边界上挂着 [IncomingRule] 那套口径，绕过去就没人执行了。
     */
    internal val messages: MessageDao,
    private val pending: PendingMessageDao,
    internal val conversations: ConversationDao,
) {
    private val log = IMLog.tag("IM.Msg")

    // ————————————————— 读 —————————————————

    fun observeConversations(owner: String): Flow<List<ConversationEntity>> =
        conversations.observeList(owner)

    /**
     * 读一行会话的**当前**状态。
     *
     * 存在的理由：页面拿到的 `ConversationEntity` 是点进来那一刻的快照，
     * 而"本地齐不齐"（`syncedConvSeq` vs `lastConvSeq`，见 [ChatSearch.isLocalComplete]）
     * 要的是此刻的值——同步正跑着的时候两者差得很远。
     */
    suspend fun conversation(owner: String, convId: String): ConversationEntity? =
        conversations.byId(owner, convId)

    /**
     * 观察一个会话的**最近 [limit] 条**消息，返回显示序（旧→新）。
     *
     * DAO 按 DESC 取最新 N 条，这里反转。**不要在 SQL 里用 ASC + LIMIT**——
     * 那会取到最旧的 N 条。
     */
    fun observeMessages(owner: String, convId: String, limit: Int): Flow<List<MessageEntity>> =
        messages.observeWindow(owner, convId, limit).map { it.asReversed() }

    suspend fun messageCount(owner: String, convId: String): Int = messages.countIn(owner, convId)

    fun observePending(owner: String, convId: String): Flow<List<PendingMessageEntity>> =
        pending.observe(owner, convId)

    fun observeTotalUnread(owner: String): Flow<Int> = conversations.observeTotalUnread(owner)

    suspend fun syncCursors(owner: String): List<Pair<String, Long>> =
        conversations.all(owner).map { it.convId to it.syncedConvSeq }

    // ————————————————— 发 —————————————————

    /** 更新待发消息的正文（上传完把本地 uri 换成服务端 url）。 */
    suspend fun updatePendingContent(owner: String, cid: String, content: String, fileSize: Long?) {
        val p = pending.byClientId(owner, cid) ?: return
        pending.put(p.copy(content = content, fileSize = fileSize ?: p.fileSize))
    }

    /** 生成一条待发消息并落库。调用方拿返回值去发帧。 */
    suspend fun createPending(
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
        /** @提及片段的 JSON（见 [PendingMessageEntity.mentionSpans]）。 */
        mentionSpans: String? = null,
        /** 被 @ 的 uid 列表 JSON（见 [PendingMessageEntity.mentions]，重名成员反推不出来）。 */
        mentions: String? = null,
    ): PendingMessageEntity {
        val p = PendingMessageEntity(
            ownerUid = owner,
            clientMsgId = UUID.randomUUID().toString(),
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
    suspend fun updatePendingMedia(
        owner: String,
        cid: String,
        mediaW: Int? = null,
        mediaH: Int? = null,
        duration: Int? = null,
        poster: String? = null,
        thumb: String? = null,
    ) {
        val p = pending.byClientId(owner, cid) ?: return
        pending.put(
            p.copy(
                mediaW = mediaW ?: p.mediaW,
                mediaH = mediaH ?: p.mediaH,
                duration = duration ?: p.duration,
                poster = poster ?: p.poster,
                thumb = thumb ?: p.thumb,
            ),
        )
    }

    /**
     * 给一条**已确认**的消息补上极小缩略（老消息补种）。
     *
     * 为什么需要：`thumb` 是随消息走的，本端接这个字段之前收发的、以及三端历史消息
     * 都没有——协议里明写**服务端不做回溯**。但客户端可以：原图在本地已经有了之后，
     * 自己算一张缩略存起来，**下次进这个会话就有磨砂占位了**。
     * 只补本机，不上行（那条消息在服务端的字节不该被后来的客户端改写）。
     */
    suspend fun setThumb(owner: String, convId: String, convSeq: Long, thumb: String) {
        val m = messages.byConvSeq(owner, convId, convSeq) ?: return
        if (!m.thumb.isNullOrBlank()) return
        messages.upsert(m.copy(thumb = thumb))
    }

    /** 在途未确认的消息——重连后按同一 `client_msg_id` 重发。 */
    suspend fun inFlight(owner: String): List<PendingMessageEntity> = pending.inFlight(owner)

    /**
     * ack 到达：落真身、清待发、bump 会话。
     *
     * **正文要从 pending 里取**——ack 只回 id/seq/时间戳，不回带正文（PROTOCOL §4.2）。
     * pending 已被清掉（重复 ack / 进程重启后重发）时只能落一条没正文的骨架，
     * 靠后续 sync 用同一 conv_seq 幂等补全。
     */
    suspend fun onAck(owner: String, ack: AckData) {
        val cached = pending.byClientId(owner, ack.clientMsgId)
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
        messages.upsert(row)
        pending.remove(owner, ack.clientMsgId)
        bumpConversation(owner, ack.convId, row)
        log.i("msg_acked", "convId" to ack.convId, "seq" to ack.convSeq, "cid" to ack.clientMsgId)
    }

    /**
     * 服务端拒绝了某条发送（PROTOCOL §8：error 带 client_msg_id）。
     * 常见码：200102 被拉黑 / 200103 非好友 / 300004 被禁言 / 300203 不是群成员。
     */
    suspend fun onSendRejected(owner: String, err: ErrorData) {
        val cid = err.clientMsgId ?: return
        pending.markState(owner, cid, SendState.Failed.name, err.code)
        log.w("msg_send_rejected", "cid" to cid, "code" to err.code)
    }

    // ————————————————— 收 —————————————————

    /**
     * 收到一条消息（new_msg 或 sync_resp 里的一条）。
     *
     * 幂等：主键 `(owner, convId, convSeq)` upsert，重复补拉自动收敛。
     */
    suspend fun onIncoming(owner: String, m: MessageData, bumpUnread: Boolean) {
        if (routeNonMessage(owner, m)) return
        val row = m.toEntity(owner)
        messages.upsert(row)
        bumpConversation(owner, m.convId, row, incUnread = bumpUnread && m.from != owner)
    }

    /**
     * 批量落库（sync_resp）。返回首个失败的 conv_seq；全部成功返回 null。
     *
     * **`msg_op` 事件行与已删墓碑在这里就被摘走**（[IncomingRule]），不进 `message` 表。
     * 这不影响游标：游标推进只认服务端给的 `covered_conv_seq`（[SyncCursorRule]），
     * 与本端存了几条无关——那些序号确实"问过了"，只是它们不成为消息。
     */
    suspend fun onIncomingBatch(owner: String, list: List<MessageData>): Long? {
        if (list.isEmpty()) return null
        val plain = mutableListOf<MessageData>()
        for (m in list) {
            if (!routeNonMessage(owner, m)) plain += m
        }
        if (plain.isEmpty()) return null
        return try {
            messages.upsert(plain.map { it.toEntity(owner) })
            null
        } catch (e: Exception) {
            log.w("msg_batch_write_failed", "count" to plain.size, "err" to e.javaClass.simpleName)
            // 逐条重试，定位首个失败点——游标只能推进到它之前（SyncCursorRule）
            var firstFailed: Long? = null
            for (m in plain.sortedBy { it.convSeq }) {
                try {
                    messages.upsert(m.toEntity(owner))
                } catch (_: Exception) {
                    firstFailed = m.convSeq
                    break
                }
            }
            firstFailed
        }
    }

    /**
     * 不是「一条聊天消息」的那两类，在**入库边界**就处理掉（PROTOCOL §6.7 离线收敛）。
     * 回 `true` = 已处理，调用方不要再落库。判据在 [IncomingRule]，对端是 im-web 的
     * `processIncoming` 开头那两个分支。
     */
    private suspend fun routeNonMessage(owner: String, m: MessageData): Boolean =
        when (IncomingRule.kindOf(m.contentType, m.deletedAt)) {
            IncomingKind.Message -> false
            IncomingKind.ApplyMsgOp -> {
                // `content` 是自描述 JSON（= msg_op 上行负载）。**非法负载忽略不崩**：
                // 老/新版本之间字段可能对不上，为一条事件行崩掉整页同步是不划算的。
                runCatching {
                    ProtocolJson.decodeFromString(MsgOpData.serializer(), m.content)
                }.onSuccess { applyMsgOp(owner, it) }
                    .onFailure {
                        log.w("msg_op_row_undecodable", "convId" to m.convId, "seq" to m.convSeq)
                    }
                true
            }
            IncomingKind.RemoveDeleted -> {
                // 「为所有人删除」的目标行：物理移除、不显墓碑（区别于 recall）。
                // 这条是**兜底**——正常情况下那条 msg_op 事件行会做同样的事；
                // 但只拿到目标行、漏了事件行时（直加载/整页同步），没有它就会误显已删内容。
                messages.delete(owner, m.convId, m.convSeq)
                true
            }
        }

    /**
     * 收敛历史遗留的 `msg_op` 事件行。
     *
     * 本端在 2026-09-09 之前没有 [IncomingRule] 那道口径，把事件行当普通消息落了库——
     * 症状是聊天页里冒出裸 JSON 气泡，**病根是那几次撤回/编辑/置顶/删除从来没被应用**。
     * 改了入库口径只能管住以后的，已经躺在库里的那些得在这里补课：
     * **先应用效果、再删行**，顺序反了就等于把那几次操作永久丢掉。
     *
     * 每次连上都跑一次：第一次之后库里就没有这种行了，之后是一次走索引的空查询。
     * 回本次清掉的行数（0 = 干净）。
     */
    suspend fun convergeLegacyMsgOpRows(owner: String, limit: Int = 500): Int {
        val rows = messages.legacyMsgOpRows(owner, limit)
        if (rows.isEmpty()) {
            // 也记一笔：不然"跑没跑过"无从判断（这一步一辈子只跑一次，没日志就查不了）
            log.i("legacy_msg_op_converged", "applied" to 0, "deleted" to 0)
            return 0
        }
        for (r in rows) {
            runCatching { ProtocolJson.decodeFromString(MsgOpData.serializer(), r.content) }
                .onSuccess { applyMsgOp(owner, it) }
                .onFailure { log.w("legacy_msg_op_undecodable", "convId" to r.convId, "seq" to r.convSeq) }
        }
        val gone = messages.deleteLegacyMsgOpRows(owner)
        log.i("legacy_msg_op_converged", "applied" to rows.size, "deleted" to gone)
        return gone
    }

    /** 推进同步游标。**只接受 [SyncCursorRule] 算出的值。** */
    suspend fun advanceCursor(owner: String, convId: String, covered: Long, firstFailedSeq: Long?) {
        val cur = conversations.byId(owner, convId)?.syncedConvSeq ?: 0
        val next = SyncCursorRule.advance(cur, covered, firstFailedSeq)
        if (next != cur) {
            conversations.setSyncedConvSeq(owner, convId, next)
            log.d("sync_cursor_advanced", "convId" to convId, "from" to cur, "to" to next)
        }
    }

    /**
     * 会话列表整表刷新（HTTP 拉回来的权威快照）。
     *
     * @param presence 顺带把在线态快照喂进去。**HTTP 快照才是初始值来源**，
     *   `presence` 帧只用于其后的增量更新（PROTOCOL §5.5「档位只是变化通知，不是初始值来源」）。
     *   不喂的话，进会话时对端在线态一律空白，要等下一次 presence 帧才显示。
     */
    suspend fun applyConversationList(
        owner: String,
        list: List<ConversationSummary>,
        presence: PresenceStore? = null,
    ) {
        list.forEach { s ->
            if (!s.isGroup && s.peer.isNotEmpty()) {
                presence?.seed(s.peer, s.peerPresence, s.peerOnlineUntil, s.peerLastSeen)
            }
        }
        val rows = list.map { s ->
            // 游标是本地状态，服务端快照里没有——**必须保留原值**，
            // 否则每次刷新会话列表都会把同步进度清零，触发全量重拉。
            val existing = conversations.byId(owner, s.convId)
            ConversationEntity(
                ownerUid = owner,
                convId = s.convId,
                isGroup = s.isGroup,
                peerUid = s.peer,
                title = DisplayName.ofConversation(s),
                avatarUrl = if (s.isGroup) s.avatarUrl else s.peerAvatarUrl,
                peerRemark = s.peerRemark,
                lastContent = s.lastMessage?.let { previewOf(it) } ?: "",
                lastContentType = s.lastMessage?.contentType ?: ContentType.TEXT,
                lastTimestamp = s.lastMessage?.timestamp ?: 0,
                lastConvSeq = s.latestConvSeq,
                unread = s.unread,
                mentionUnread = s.mentionUnread,
                readSeq = s.readSeq,
                peerReadSeq = s.peerReadSeq,
                syncedConvSeq = existing?.syncedConvSeq ?: 0,
                pinnedAt = s.pinnedAt,
                muted = s.muted,
                markedUnread = s.markedUnread,
            )
        }
        conversations.upsert(rows)
        log.i("conversations_applied", "count" to rows.size)
    }

    suspend fun markRead(owner: String, convId: String, upTo: Long) {
        conversations.markRead(owner, convId, upTo)
    }

    suspend fun applyPeerReceipt(owner: String, r: ReceiptData) {
        if (r.status != ReceiptData.READ) return
        val c = conversations.byId(owner, r.convId) ?: return
        if (r.from == owner) {
            // 本人其它端已读 → 本端未读清零（多端已读同步）
            conversations.markRead(owner, r.convId, maxOf(c.readSeq, r.upToConvSeq))
        } else {
            conversations.upsert(c.copy(peerReadSeq = maxOf(c.peerReadSeq, r.upToConvSeq)))
        }
    }

    /**
     * 应用一条 msg_op（§6.7）。
     *
     * **recall 与 delete 的收端行为完全不同**：
     * - `recall` → 置 `recalledAt`，**保留行**渲染成墓碑（"XX 撤回了一条消息"）
     * - `delete` → **物理移除该消息**，不显墓碑
     * 搞反了就是「撤回后消息消失得无影无踪」或「删除后留了个墓碑」，两种都不对。
     */
    suspend fun applyMsgOp(owner: String, op: MsgOpData) {
        val target = messages.byConvSeq(owner, op.convId, op.targetConvSeq)
        when (op.op) {
            MsgOp.RECALL -> {
                if (target == null) return
                messages.upsert(target.copy(recalledAt = op.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()))
            }
            MsgOp.DELETE -> messages.delete(owner, op.convId, op.targetConvSeq)
            MsgOp.EDIT -> {
                if (target == null) return
                messages.upsert(
                    target.copy(
                        content = op.content ?: target.content,
                        editedAt = op.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                )
            }
            MsgOp.PIN -> {
                if (target == null) return
                // pinned 恒带（非 omitempty）；null 只可能是老服务端，按"不变"处理
                val p = op.pinned ?: return
                messages.upsert(target.copy(pinnedAt = if (p) (op.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()) else null))
            }
        }
        log.i("msg_op_applied", "op" to op.op, "convId" to op.convId, "target" to op.targetConvSeq)
    }

    /** 应用 conv_update（§6.8）。下行**携带变更后的完整状态**，直接覆盖本地。 */
    suspend fun applyConvUpdate(owner: String, u: ConvUpdateData) {
        val c = conversations.byId(owner, u.convId) ?: return
        conversations.upsert(
            c.copy(
                pinnedAt = u.pinnedAt,
                muted = u.muted,
                markedUnread = u.markedUnread,
            )
        )
        log.i("conv_update_applied", "convId" to u.convId, "action" to u.action)
    }

    /** 「仅为我删除」：收端**物理移除**（§6.7.1）。 */
    suspend fun applyMsgHidden(owner: String, convId: String, convSeq: Long) {
        messages.delete(owner, convId, convSeq)
        log.i("msg_hidden_applied", "convId" to convId, "seq" to convSeq)
    }

    /**
     * 清空一条会话在**本机**的消息（详情页「清空聊天记录」）。
     *
     * **只删本地，不动服务端**——与 iOS `clearMessagesForConv:` 同一口径
     * （群聊那句提示就写着"仅清空本机记录，不影响其他成员"）。服务端没有对应接口，
     * 也不该有：那会变成"替所有人删历史"。
     *
     * **同步游标 `syncedConvSeq` 原样保留**：清空后不该再把刚删掉的那些拉回来。
     * 会话行留着（列表里仍能看到这个人），只把预览清成空。
     */
    suspend fun clearConversation(owner: String, convId: String) {
        messages.clearConv(owner, convId)
        pending.clearConv(owner, convId)
        conversations.byId(owner, convId)?.let {
            conversations.upsert(it.copy(lastContent = "", lastContentType = "text", lastTimestamp = 0))
        }
        log.i("conv_history_cleared", "convId" to convId)
    }

    suspend fun clearAccount(owner: String) {
        messages.clearAccount(owner)
        pending.clearAccount(owner)
        conversations.clearAccount(owner)
    }

    // ————————————————— 内部 —————————————————

    private suspend fun bumpConversation(
        owner: String,
        convId: String,
        row: MessageEntity,
        incUnread: Boolean = false,
    ) {
        val c = conversations.byId(owner, convId) ?: ConversationEntity(
            ownerUid = owner,
            convId = convId,
            isGroup = convId.startsWith("g_"),
        )
        conversations.upsert(
            c.copy(
                lastContent = previewOfEntity(row),
                lastContentType = row.contentType,
                lastTimestamp = maxOf(c.lastTimestamp, row.timestamp),
                lastConvSeq = maxOf(c.lastConvSeq, row.convSeq),
                unread = if (incUnread) c.unread + 1 else c.unread,
            )
        )
    }

    private fun previewOf(m: MessageData): String = when (m.contentType) {
        ContentType.TEXT -> m.content
        ContentType.IMAGE -> m.caption?.takeIf { it.isNotBlank() } ?: "[图片]"
        ContentType.VIDEO -> m.caption?.takeIf { it.isNotBlank() } ?: "[视频]"
        ContentType.VOICE -> "[语音]"
        ContentType.FILE -> m.caption?.takeIf { it.isNotBlank() } ?: "[文件]"
        ContentType.CONTACT -> "[个人名片]"
        ContentType.CHAT_RECORD -> "[聊天记录]"
        ContentType.SYSTEM -> m.content
        else -> m.content
    }

    private fun previewOfEntity(m: MessageEntity): String = when (m.contentType) {
        ContentType.TEXT -> m.content
        ContentType.IMAGE -> m.caption?.takeIf { it.isNotBlank() } ?: "[图片]"
        ContentType.VIDEO -> m.caption?.takeIf { it.isNotBlank() } ?: "[视频]"
        ContentType.VOICE -> "[语音]"
        ContentType.FILE -> m.caption?.takeIf { it.isNotBlank() } ?: "[文件]"
        ContentType.CONTACT -> "[个人名片]"
        ContentType.CHAT_RECORD -> "[聊天记录]"
        else -> m.content
    }
}

private fun MessageData.toEntity(owner: String) = MessageEntity(
    ownerUid = owner,
    convId = convId,
    convSeq = convSeq,
    serverMsgId = serverMsgId,
    sender = from,
    fromNickname = fromNickname,
    fromRole = fromRole,
    contentType = contentType,
    content = content,
    caption = caption,
    timestamp = timestamp,
    fileName = fileName,
    fileSize = fileSize,
    mediaW = mediaW,
    mediaH = mediaH,
    duration = duration,
    poster = poster,
    thumb = thumb,
    waveform = waveform,
    replyToConvSeq = replyToConvSeq,
    replySnapshot = replySnapshot,
    replyToFrom = replyToFrom,
    recalledAt = recalledAt,
    deletedAt = deletedAt,
    editedAt = editedAt,
    pinnedAt = pinnedAt,
    forwardFrom = forwardFrom,
    groupId = groupId,
    // 分段落库：不落的话刷新/重进会话后系统消息退回"显真实昵称、不可点"
    sysSegments = sysSegments?.takeIf { it.isNotEmpty() }?.let {
        ProtocolJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SysSegment.serializer()), it)
    },
    // @提及片段落库：不落的话重进会话后 @ 不再高亮、也点不动（同上一条的坑）
    mentionSpans = Mention.encodeSpans(mentionSpans.orEmpty()),
)

/**
 * 本地一页搜索结果。
 *
 * 单独一个类型只为带上 [truncated]：命中被单页上限截断时计数要补 `+`，
 * 而这件事只有**查询层**知道（UI 拿到的是复核过滤之后的列表，长度反推不出来）。
 */
data class LocalSearchPage(
    val rows: List<MessageEntity>,
    val truncated: Boolean,
)
