package com.libeyond.imandroid.data

/**
 * 「清除到位点」例程（OFFLINE_BACKLOG_DESIGN §6.7 / §6.7.1）：清该会话 ≤ 位点的消息 + 区间清单裁到位点之后 + 抬清空位点 + 游标抬到位点，同一事务。
 * 「清空聊天记录」、本机「删除会话」、别端 `conv_update(delete)`、会话列表快照里 `cleared_at` 高过本机位点，四处共用这一条，
 * 位点口径只在 [ClearFloor.floorOnClear]。放在这里只为控 `MessageRepository.kt` 的体量。
 *
 * 与 iOS / Web 同口径：有服务端 `cleared_at` 时**只清 ≤ 位点**，位点之上已落库的消息与待发消息原样保留（区间裁到 `位点+1` 起，游标只增不减）；
 * 无服务端位点（本机「清空聊天记录」/ DELETE 回包缺字段）位点取本机所知最新，等于整会话清空（含待发）。
 * 调用方负责丢 `HistoryFloors` 缓存（[MessageService] 持有它）。
 *
 * 位点随会话行存放，**行删后位点不保留**（iOS 另有独立小表）。不需要：服务端 `convAccessFor` 已按 `cleared_at` 过滤
 * sync / window / 搜索 / 媒体，复现时即便位点暂为 0 也拉不回旧史；复现壳行由 bump 触发列表刷新，列表的 `cleared_at`
 * 恢复位点（[MessageRepository.applyConversationList] 的 `max`）。窗口内的唯一副作用是游标 0 → 一次以 `since=0` 的 sync（服务端从下界起给）。
 *
 * @param removeRow 删除会话：同事务连会话行一并移走；位点以内才移——位点之上已有消息（迟到的回声帧）则行保留，见 [ClearFloor.revivedAfter]。
 * @return 本次生效的位点（0 = 无可清）
 */
suspend fun MessageRepository.clearToFloor(owner: String, convId: String, serverClearedAt: Long, removeRow: Boolean = false): Long {
    val conv = conversations.byId(owner, convId)
    val maxLocal = messages.maxConvSeq(owner, convId) ?: 0
    val floor = ClearFloor.floorOnClear(
        localCleared = ClearFloor.effective(conv?.clearedUpTo ?: 0, ranges.clearFloor(owner, convId)),
        serverClearedAt = serverClearedAt,
        localHead = ClearFloor.floorAtClear(
            head = conv?.headConvSeq ?: 0, lastConvSeq = conv?.lastConvSeq ?: 0,
            synced = conv?.syncedConvSeq ?: 0, maxLocalSeq = maxLocal,
        ),
    )
    val keepNewer = serverClearedAt > 0
    val dropRow = removeRow && !ClearFloor.revivedAfter(serverClearedAt, conv?.lastConvSeq ?: 0, maxLocal)
    tx.run {
        if (keepNewer) {
            messages.deleteUpTo(owner, convId, floor)
            ranges.clipAfter(owner, convId, floor) // 与消息同事务：崩在中间会留下「清单说齐、消息已空」
        } else {
            messages.clearConv(owner, convId)
            ranges.clearConv(owner, convId)
        }
        ranges.raiseClearFloor(owner, convId, floor) // 独立位点表：行删了也在（iOS im_conv_clear_floor_local 同款）
        if (dropRow) {
            conversations.deleteConv(owner, convId)
        } else if (floor > 0) {
            conversations.raiseClearedUpTo(owner, convId, floor)
            conversations.raiseSyncedConvSeq(owner, convId, floor)
        }
    }
    if (!keepNewer) pending.clearConv(owner, convId) // 待发消息：服务端位点口径下保留（它们发出后 conv_seq 必在位点之上）
    log.i("conv_cleared_to_floor", "convId" to convId, "floor" to floor, "server" to serverClearedAt, "removeRow" to dropRow, "keptNewer" to keepNewer)
    return floor
}
