package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MessageData

/**
 * 「落一页 + 登记区间」的两个入口（C1，OFFLINE_BACKLOG_DESIGN §4.2 / §4.6）。核心在
 * [MessageRepository.persistPage]；放在这里只为控 `MessageRepository.kt` 的体量，写成扩展函数，调用点不变形。
 *
 * 对称兄弟：iOS `IMDatabase+Ranges.m` 的 `saveIncomingPage:advanceTo:rangeLo:rangeHi:`（sync 页）与
 * `IMSocketManager.m` 的 `handleWindowResp:`（窗口）。**对称的是不变式，不是形状**：iOS 的窗口路径是「逐条存、再另开事务登记」，
 * 这里一并包进事务，更严格、没有坏处。
 */

/**
 * 一页 `sync_resp`：落库 + 登记 `(游标, covered]` + 推进游标，同一事务。返回首个失败序号（全成功 null）。
 *
 * 登记的是 `(since, covered]` 而不是「实际收到的最小到最大」：服务端断言这段内每个序号要么已下发、要么对我不可见
 * （`history_visible`、仅为我删除、事件行、墓碑），与 [SyncCursorRule] 同口径。`too_long`（`covered == since`、无消息）不登记。
 */
suspend fun MessageRepository.onSyncPage(
    owner: String,
    convId: String,
    list: List<MessageData>,
    covered: Long,
    /** 本页请求时的游标。调用方已有（一次 sync_resp 里几十个会话）就传，省得每页各读一次库；不传则现读。 */
    sinceHint: Long? = null,
): Long? {
    val since = sinceHint ?: (conversations.byId(owner, convId)?.syncedConvSeq ?: 0L)
    val range = if (covered > since) SeqRange(since + 1, covered) else null
    return persistPage(owner, convId, list, range, covered)
}

/**
 * 一窗 `window_resp`：落库 + 登记这一窗覆盖到的 `[最小, 最大]` conv_seq，**不推进同步游标**（窗口是一次性快照）。
 * 区间含事件行与墓碑——它们同样是「服务端给过了」，只是不成为气泡；漏掉的话这一段永远进不了目录，上滑每页都得重问服务端。
 */
suspend fun MessageRepository.onWindowPage(owner: String, convId: String, list: List<MessageData>): Long? =
    persistPage(owner, convId, list, SyncRanges.spanOf(list.map { it.convSeq }), covered = null)
