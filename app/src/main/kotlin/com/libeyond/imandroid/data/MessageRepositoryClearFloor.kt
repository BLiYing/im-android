package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ClearedConv

/**
 * 「清除到位点」例程（OFFLINE_BACKLOG_DESIGN §6.7 / §6.7.1）：清该会话 ≤ 位点的消息 + 区间清单裁到位点之后 + 抬清空位点 + 游标抬到位点，同一事务。
 * 「清空聊天记录」、本机「删除会话」、别端 `conv_update(delete)`、会话列表快照里 `cleared_at` 高过本机位点，四处共用这一条，
 * 位点口径只在 [ClearFloor.floorOnClear]。放在这里只为控 `MessageRepository.kt` 的体量。
 *
 * 与 iOS / Web 同口径：有服务端 `cleared_at` 时**只清 ≤ 位点**，位点之上已落库的消息与待发消息原样保留（区间裁到 `位点+1` 起，游标只增不减）；
 * 无服务端位点（本机「清空聊天记录」/ DELETE 回包缺字段）位点取本机所知最新，等于整会话清空（含待发）。
 * 调用方负责丢 `HistoryFloors` 缓存（[MessageService] 持有它）。
 *
 * 位点同时写独立小表 `conv_clear_floor_local`（与 iOS 同构），会话行删除后位点仍在；复现壳行带着它出生。
 * 「清除所有对话」走 [clearAllToFloors]：同一段事务体 [clearInTx]，N 个会话放进**一个**事务。
 *
 * @param removeRow 删除会话：同事务连会话行一并移走；位点以内才移——位点之上已有消息（迟到的回声帧）则行保留，见 [ClearFloor.revivedAfter]。
 * @return 本次生效的位点（0 = 无可清）
 */
suspend fun MessageRepository.clearToFloor(owner: String, convId: String, serverClearedAt: Long, removeRow: Boolean = false): Long {
    val r = tx.run { clearInTx(owner, convId, serverClearedAt, removeRow) }
    if (serverClearedAt <= 0) pending.clearConv(owner, convId) // 待发消息：服务端位点口径下保留（它们发出后 conv_seq 必在位点之上）
    log.i("conv_cleared_to_floor", "convId" to convId, "floor" to r.floor, "server" to serverClearedAt, "removeRow" to r.rowDropped, "keptNewer" to (serverClearedAt > 0))
    return r.floor
}

/**
 * 「清除所有对话」本机落地：对 [items] 每个会话各跑一遍 [clearInTx]（位点取服务端 `items[].cleared_at`，不自造），整批**单事务**——
 * 中途崩溃不会留下「一半会话清了」。回声 `conv_update(delete)` 随后到达走 [clearToFloor]，位点只增不减，幂等。
 * @return 处理的会话数。
 */
suspend fun MessageRepository.clearAllToFloors(owner: String, items: List<ClearedConv>): Int {
    if (items.isEmpty()) return 0
    tx.run { items.forEach { clearInTx(owner, it.convId, it.clearedAt, removeRow = true) } }
    items.filter { it.clearedAt <= 0 }.forEach { pending.clearConv(owner, it.convId) }
    log.i("conv_cleared_all", "count" to items.size)
    return items.size
}

internal data class ClearOutcome(val floor: Long, val rowDropped: Boolean)

/** 清除到位点的事务体（调用方负责 `tx.run`）：读与写同一事务，批量版共用。 */
internal suspend fun MessageRepository.clearInTx(owner: String, convId: String, serverClearedAt: Long, removeRow: Boolean): ClearOutcome {
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
    val dropRow = removeRow && !ClearFloor.revivedAfter(serverClearedAt, conv?.lastConvSeq ?: 0, maxLocal)
    if (serverClearedAt > 0) {
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
    return ClearOutcome(floor, dropRow)
}
