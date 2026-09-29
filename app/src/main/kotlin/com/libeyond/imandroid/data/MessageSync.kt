package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.protocol.ErrorData
import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.SyncCursorItem
import com.libeyond.imandroid.sdk.protocol.SyncDefaults
import com.libeyond.imandroid.sdk.protocol.SyncReqData
import com.libeyond.imandroid.sdk.protocol.SyncRespData

/**
 * [MessageService] 的**连接后编排与增量同步**：重连四件事 / `sync_req` / 落一页 `sync_resp` /
 * 在途重发。
 *
 * 从 MessageService 平移出来（2026-09-16，那个文件到 580/600 行），**行为未改**。
 * 切口就是那个类自己 KDoc 上写的那道缝——「**帧分派** + **同步编排**」：分派留在原处
 * （它要贴着协议帧类型走），编排搬到这里。这一组的共同点是「**由连接状态驱动、按游标推进**」，
 * 与 `sendText` 那种「由用户动作驱动」的路径没有共享状态，所以切开不会把状态机切散。
 * 这里也是 Android 端补离线积压（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1
 * 那几项：`max_gap` / 区间清单 / sync 路径回 `delivered`）时要动的那个文件。
 *
 * 写成扩展函数而不是搬进另一个类：**调用形态一处不用改**，同 `MessageSignals` /
 * `MessageMsgOps` / `MessageWindowQueries` 的取舍。代价是 `repo` / `ownerProvider` / `media` / `transmit`
 * 从 private 放宽到 internal——**Kotlin 的 internal 是模块级，等于对整个 `:app` 敞开**（`ui` 包也够得着），
 * 编译器守不住，靠约定：细则与"review 该打回的信号"记在 `MessageService.repo` 的注释上。
 */

/**
 * 连上之后做四件事，**顺序有讲究**：先把权威快照拉回来，再按游标补拉，然后补发在途的，
 * 最后重订在线态。
 */
internal suspend fun MessageService.onConnected() {
    val owner = ownerProvider() ?: return
    refreshConversations()
    requestSync(owner)
    resendInFlight(owner)
    // watch 订阅是**连接级易失态**，断连即清 → 重连必须重发当前集合，
    // 否则重连后所有在线态就此冻在旧值上（PROTOCOL §5.5「生命周期」）。
    sendWatch(presence.currentWatchSet().toSet(), force = true)
}

/**
 * 按各会话的本地游标发一次 `sync_req`（增量补拉）。开窗取数走 `windows`，不是这一路。
 *
 * **每条游标都带 `max_gap`**（OFFLINE_BACKLOG_DESIGN §4.11.1 C2，Android 审计建议的第一条）：
 * 不带的话服务端不限深度、追平为止——超级群重连也会把积压整段抄完（`IncomingRule.kt` 头注释
 * 记着 2026-09-09 在 11 万条大群真机撞见过）。恒发 [SyncDefaults.MAX_GAP]，暂不按超级群降到
 * 0（那需要本地知道"这个会话是不是超级群"，`ConversationEntity` 目前不落这一列，留给 C1）。
 */
internal suspend fun MessageService.requestSync(owner: String) {
    val cursors = repo.syncCursors(owner).map { (convId, seq) -> SyncCursorItem(convId, seq, maxGap = SyncDefaults.MAX_GAP) }
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
internal suspend fun MessageService.applySync(owner: String, resp: SyncRespData) {
    var needMore = false
    val nextCursors = mutableListOf<SyncCursorItem>()
    for (c in resp.conversations) {
        val firstFailed = repo.onIncomingBatch(owner, c.messages)
        // 补收的这一页要跟着 bump 会话列表快照，否则重连补收的消息只进聊天页、
        // 不进列表排序（见 MessageRepository.bumpConversationFromLatest 的注释）。
        if (c.messages.isNotEmpty()) repo.bumpConversationFromLatest(owner, c.convId)
        repo.advanceCursor(owner, c.convId, c.coveredConvSeq, firstFailed)
        log.i(
            "sync_page_applied",
            "convId" to c.convId, "msgs" to c.messages.size,
            "covered" to c.coveredConvSeq, "hasMore" to c.hasMore,
        )
        // 服务端判它「太长了」：本页没有消息、游标原地不动（advanceCursor 上面那行是空写）。
        // 只留痕，不重试——重试只会立刻拿到同一个 too_long（缺口本身要等 C1 落地才谈得上按需补）。
        if (c.tooLong) {
            log.i("sync_backlog_too_long", "convId" to c.convId, "head" to c.headConvSeq)
        }
        if (c.hasMore && firstFailed == null) {
            needMore = true
            // 续页沿用同一个 max_gap 预算：page 1 通过闸门后，别让 page 2 在两页之间悄悄变回不限深度
            // （极端场景：page 1 与 page 2 之间又涌进一大批新消息，把 head 顶远了）。
            nextCursors += SyncCursorItem(c.convId, SyncCursorRule.nextSince(c.coveredConvSeq), maxGap = SyncDefaults.MAX_GAP)
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

/**
 * 用某会话此刻已落库的最新一条 bump 会话列表快照（预览文案 / `lastTimestamp` / `lastConvSeq`），
 * **不碰未读**——未读走上面 [applySync] 末尾 `refreshConversations()` 的服务端权威值，这里瞎加会跟它打架。
 *
 * 断线重连补收（`sync_resp`）专用：[MessageRepository.onIncomingBatch] 只管落 `message` 表，
 * 从不碰 `conversation` 表，于是补收的这批消息只进得了聊天页、进不了会话列表排序——会话不上移、
 * 预览还停在断线前那条（2026-09-28 用户报「收到新消息，会话没有上移到前面」）。
 * `window_resp`（锚点开窗，见 [MessageService] 的 `WINDOW_RESP` 分支）**不该**调用这个：
 * 那是翻历史，把很久以前的锚点消息当"最新一条"bump 会把会话错误地顶到最前面。
 */
internal suspend fun MessageRepository.bumpConversationFromLatest(owner: String, convId: String) {
    // 落一层保护：DB 读/写偶发失败（磁盘压力等）不该掀翻整页 sync_resp——
    // 那会连累这一页里排在后面的会话全部推进不了游标（applySync 没有外层 try/catch，
    // 一异常整个 for 循环连 advanceCursor 都不做了）。失败只跳过这一次 bump，不影响主流程。
    try {
        val latest = messages.latestWindow(owner, convId, 1).firstOrNull() ?: return
        bumpConversation(owner, convId, latest)
    } catch (e: Exception) {
        log.w("conv_bump_from_latest_failed", "convId" to convId, "err" to e.javaClass.simpleName)
    }
}

/** 重连后把在途未确认的消息按同一 client_msg_id 重发。 */
internal suspend fun MessageService.resendInFlight(owner: String) {
    val list = repo.inFlight(owner)
    if (list.isEmpty()) return

    // **正文还是本地 uri 的媒体消息不能原样重发**：那是「上传没走完就被杀进程/断线」的残留。
    // 图片/视频的本地 uri 是系统相册 `content://`，读权限随发起进程一起没了，重发无从谈起，
    // 原样发出去服务端会把这段地址当正文存下来，收件人拿到一个**永远打不开的地址**、还改不回来，
    // 只能标失败让用户重选一次。**语音刻意不同**：本地文件在应用私有目录（`file://`），
    // 进程重启后依然读得到，能重新上传，不必让用户重录（与 [MessageService.resend] 同一处理，
    // 2026-09-28 code review 抓出：这里此前一律标失败，唯独漏了语音这一支，两处判据不对称）。
    // **正在上传的那几条既不重发也不标失败**——它们的上传协程还在跑，
    // 标失败会让用户看到红❗，而几秒后它自己又发出去了（见 MediaSendPipeline.uploading）。
    val inProgress = list.filter { isLocalUri(it.content) && media.isUploading(it.clientMsgId) }
    val rest = list - inProgress.toSet()
    if (inProgress.isNotEmpty()) log.i("resend_skipped_uploading", "count" to inProgress.size)
    val (resendable, localUri) = rest.partition { !isLocalUri(it.content) }
    val (voiceStale, stale) = localUri.partition { it.contentType == ContentType.VOICE }
    voiceStale.forEach { media.reuploadVoice(it) }
    if (voiceStale.isNotEmpty()) log.i("resend_voice_reupload", "count" to voiceStale.size)
    stale.forEach {
        repo.onSendRejected(
            owner,
            ErrorData(
                code = ErrCode.PARAM_INVALID,
                message = Str.s(R.string.chat_resend_upload_incomplete),
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
            it.mediaW, it.mediaH, it.duration, it.poster, it.thumb, it.waveform,
            mentions = Mention.parseMentions(it.mentions),
            mentionAll = Mention.mentionAllFromSpans(it.mentionSpans),
            mentionSpans = Mention.parseSpans(it.mentionSpans),
        )
    }
}

/**
 * 判断一条待发消息的正文是不是**本地** uri（还没上传完）。
 *
 * 抽成顶层纯函数便于单测（`LocalUriTest`）——这条判据错了不会报错，只会让收件人收到一个
 * 打不开的 `content://` 地址，而且再也改不回来。
 */
internal fun isLocalUri(content: String): Boolean =
    content.startsWith("content://") || content.startsWith("file://")
