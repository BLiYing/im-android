package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationDao
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageDao
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageDao
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.data.db.SendState
import com.libeyond.imandroid.sdk.api.ConversationSummary
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrCode
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
 * 查看器翻页序列一次从本地取多少条媒体。
 *
 * 取的是**最新的 N 条媒体**（不是最新 N 条消息里的媒体）。超过这个数的更早部分靠翻到头时
 * 向服务端续拉（[MediaTimeline] 的注释写了为什么只能往更旧续）。不设上限的话，一条几万张图的
 * 会话进查看器就要把几万行构造成对象——与 `observeWindow` 那条教训同源。
 */
internal const val MEDIA_TIMELINE_LIMIT = 300

/** 通话记录的 client_msg_id 前缀：`call-<call_id>`（服务端凭它去重，见 [MessageService.sendCallRecord]）。 */
internal const val CALL_CID_PREFIX = "call-"

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
     * DAO 是 `internal` 而不是 `private`，为的是让 `MessageWindowQueries.kt` 够得着
     * （窗口与搜索那一组读查询，搬出去只为控体量，调用点仍写成 `repo.windowAround(...)`）。
     *
     * ⚠️ **别把这读成"只有同包碰得到"**：Kotlin 的 `internal` 是**模块级**，没有 Java 那种
     * package-private，而 `:app` 统共一个模块——`ui` 包照样够得着。所以那是**约定不是保证**，
     * 编译器不替你守。约定是：除那个文件外谁都别直接碰，写侧一律走本类的方法——
     * 入库边界上挂着 [IncomingRule] 那套口径，绕过去就没人执行了。
     */
    internal val messages: MessageDao,
    /** `internal`——同上一条注释：`MessageRepositorySend.kt`（发送链路那组）也要够得着。 */
    internal val pending: PendingMessageDao,
    internal val conversations: ConversationDao,
) {
    internal val log = IMLog.tag("IM.Msg")

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

    /** 把对端权威名片并回单聊会话行（规则见 [PeerCardMerge]）；本机没有这个会话就不动。 */
    suspend fun applyPeerCard(owner: String, peerUid: String, card: com.libeyond.imandroid.sdk.api.UserCard) {
        val row = conversations.byPeer(owner, peerUid) ?: return
        val m = PeerCardMerge.merge(row, card)
        conversations.updatePeerProfile(owner, row.convId, m.title, m.avatarUrl, m.peerRemark)
    }

    /**
     * 观察一个会话的**最近 [limit] 条**消息，返回显示序（旧→新）。
     *
     * DAO 按 DESC 取最新 N 条，这里反转。**不要在 SQL 里用 ASC + LIMIT**——
     * 那会取到最旧的 N 条。
     */
    fun observeMessages(owner: String, convId: String, limit: Int): Flow<List<MessageEntity>> =
        messages.observeWindow(owner, convId, limit).map { it.asReversed() }

    suspend fun messageCount(owner: String, convId: String): Int = messages.countIn(owner, convId)

    /**
     * 会话媒体时间线（查看器翻页的本地那一半），**升序返回**（旧→新）。
     *
     * Dao 那侧按 `convSeq DESC LIMIT n` 取最新 N 条，这里反转成序列序——
     * 写成 ASC + LIMIT 会取到最**旧**的 N 条（Dao 注释里记着的那个容易犯的错）。
     */
    suspend fun conversationMedia(
        owner: String,
        convId: String,
        limit: Int = MEDIA_TIMELINE_LIMIT,
    ): List<MessageEntity> = messages.convMedia(owner, convId, limit).asReversed()

    fun observePending(owner: String, convId: String): Flow<List<PendingMessageEntity>> =
        pending.observe(owner, convId)

    suspend fun syncCursors(owner: String): List<Pair<String, Long>> =
        conversations.all(owner).map { it.convId to it.syncedConvSeq }

    // ————————————————— 发 —————————————————
    // 待发行的创建/更新、ack/拒绝的落地——整组搬到 MessageRepositorySend.kt（CODING_STYLE §7②，
    // 本文件贴着 600 行硬闸）。写成扩展函数，调用点（`repo.createPending(...)` 等）一个字没改。

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
        bumpConversation(owner, m.convId, row, incUnread = bumpUnread && IncomingRule.countsAsUnread(m.from, m.contentType, owner))
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
                lastContent = s.lastMessage
                    ?.let { MessagePreview.of(it.contentType, it.content, it.caption, viewerIsSender = it.from == owner) } ?: "",
                lastContentType = s.lastMessage?.contentType ?: ContentType.TEXT,
                lastFrom = s.lastMessage?.from ?: "",
                lastFromNickname = s.lastMessage?.fromNickname ?: "",
                lastRecalled = (s.lastMessage?.recalledAt ?: 0L) > 0L,
                lastSysEvent = s.lastMessage?.sysEvent.orEmpty(),
                lastSysArgs = SysEvents.encodeArgs(s.lastMessage?.sysArgs).orEmpty(),
                // 与 MessageMapping.toEntity 同一种编码，直接对字段编码而不必先造一整个 MessageEntity
                lastSysSegments = s.lastMessage?.sysSegments?.takeIf { it.isNotEmpty() }?.let {
                    ProtocolJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(
                            com.libeyond.imandroid.sdk.protocol.SysSegment.serializer(),
                        ),
                        it,
                    )
                }.orEmpty(),
                lastTimestamp = s.lastMessage?.timestamp ?: 0,
                lastConvSeq = s.latestConvSeq,
                unread = s.unread,
                mentionUnread = s.mentionUnread,
                readSeq = s.readSeq,
                peerReadSeq = s.peerReadSeq,
                syncedConvSeq = existing?.syncedConvSeq ?: 0,
                pinnedAt = s.pinnedAt,
                muted = s.muted,
                muteUntil = s.muteUntil,
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
                // 撤回的恰好是会话列表当前指着的那条：翻 lastRecalled 位，副标题按 ConversationPreview
                // 现算成"撤回了一条消息"——**只翻位，不改 lastContent**：正文烤在写库那一刻，
                // 这里没有"谁发的显示名"这些上下文去重新烤一遍，也不需要（现算函数会绕过它）。
                val conv = conversations.byId(owner, op.convId)
                if (conv != null && conv.lastConvSeq == op.targetConvSeq && !conv.lastRecalled) {
                    conversations.upsert(conv.copy(lastRecalled = true))
                }
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
                muteUntil = u.muteUntil,
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
            conversations.upsert(
                it.copy(
                    lastContent = "",
                    lastContentType = "text",
                    lastTimestamp = 0,
                    lastSysEvent = "",
                    lastSysArgs = "",
                    lastSysSegments = "",
                )
            )
        }
        log.i("conv_history_cleared", "convId" to convId)
    }

    suspend fun clearAccount(owner: String) {
        messages.clearAccount(owner)
        pending.clearAccount(owner)
        conversations.clearAccount(owner)
    }

    // ————————————————— 内部 —————————————————

    /**
     * `internal` 不是 `private`：`MessageRepositorySend.kt` 的 `onAck`（ack 落库后 bump 会话）与
     * `MessageSync.kt` 的 `bumpConversationFromLatest`（断线重连补收后 bump 会话）都要复用同一份逻辑。
     */
    internal suspend fun bumpConversation(
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
                lastContent = MessagePreview.of(row.contentType, row.content, row.caption, viewerIsSender = row.sender == owner),
                lastContentType = row.contentType,
                lastFrom = row.sender,
                lastFromNickname = row.fromNickname.orEmpty(),
                // 新落的这一条必然不是撤回态——撤回是之后另一帧 msg_op 才会翻的位（见 applyMsgOp）
                lastRecalled = false,
                lastSysEvent = row.sysEvent.orEmpty(),
                lastSysArgs = row.sysArgs.orEmpty(),
                lastSysSegments = row.sysSegments.orEmpty(),
                lastTimestamp = maxOf(c.lastTimestamp, row.timestamp),
                lastConvSeq = maxOf(c.lastConvSeq, row.convSeq),
                unread = if (incUnread) c.unread + 1 else c.unread,
            )
        )
    }
}
