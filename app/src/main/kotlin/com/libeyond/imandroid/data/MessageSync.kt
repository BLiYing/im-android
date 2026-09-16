package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.protocol.ErrorData
import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.SyncCursorItem
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

/** 按各会话的本地游标发一次 `sync_req`（增量补拉）。开窗取数走 `windows`，不是这一路。 */
internal suspend fun MessageService.requestSync(owner: String) {
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
internal suspend fun MessageService.applySync(owner: String, resp: SyncRespData) {
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
internal suspend fun MessageService.resendInFlight(owner: String) {
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
            ErrorData(
                code = ErrCode.PARAM_INVALID,
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

/**
 * 判断一条待发消息的正文是不是**本地** uri（还没上传完）。
 *
 * 抽成顶层纯函数便于单测（`LocalUriTest`）——这条判据错了不会报错，只会让收件人收到一个
 * 打不开的 `content://` 地址，而且再也改不回来。
 */
internal fun isLocalUri(content: String): Boolean =
    content.startsWith("content://") || content.startsWith("file://")
