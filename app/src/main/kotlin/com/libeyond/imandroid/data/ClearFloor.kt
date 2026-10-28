package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MessageData

/**
 * 「清空聊天记录」的**本机清空位点**（[com.libeyond.imandroid.data.db.ConversationEntity.clearedUpTo]）。
 *
 * ## 为什么需要它
 * 清空只清本机、不动服务端——服务端仍有那些消息。区间清单（C1）又是「本地有哪几段」的目录：
 * 清空后若没有别的记号，进会话（C3）会看到「本地没有、服务端有」，老老实实把最近一页拉回来，
 * 用户刚清掉的内容就冒出来了。三端契约里「清空连区间一起清」只解决了「空白不自愈」，并没有解决这件事
 * （Web 因此会拉回；iOS 没清区间，碰巧不拉回）。位点把「用户**主动**不要这一段」与「**还没**下载这一段」分开。
 *
 * ## 语义
 * - 清空时位点 = 本机所知的会话最新（head / 最新一条 / 游标 / 本地最大序号 取大）；**只增不减**。
 * - ≤ 位点的消息：不再落库（[dropCleared]）、不算缺口、不再向服务端要；同步游标一并推到位点。
 * - 有效可见下界 = `max(服务端下界, clearedUpTo)`，与 `history_visible` 的下界走同一条判据（`SyncRanges.isComplete` 的 `floor`）。
 * - 位点之后的新消息照常收。
 */
object ClearFloor {

    /** 清空那一刻该取的位点：本机所知的最新位置。 */
    fun floorAtClear(head: Long, lastConvSeq: Long, synced: Long, maxLocalSeq: Long): Long =
        maxOf(head, lastConvSeq, synced, maxLocalSeq, 0L)

    /** 丢掉 ≤ 位点的消息（用户清掉的那一段，sync / window 页里可能又带回来）。位点为 0 原样返回。 */
    fun dropCleared(list: List<MessageData>, clearedUpTo: Long): List<MessageData> =
        if (clearedUpTo <= 0) list else list.filter { it.convSeq <= 0 || it.convSeq > clearedUpTo }

    /**
     * 「清除到位点」例程（删除会话 / 别端删除 / 列表快照对齐，OFFLINE_BACKLOG_DESIGN §6.7.1）取的位点：
     * 有服务端 `cleared_at` 就是 `max(本机已有位点, cleared_at)`（服务端是真相源，本机只是缓存）；
     * 没有（DELETE 回包缺字段 / 本地「清空聊天记录」）退回本机所知最新 [localHead]，同样不低于已有位点。只增不减。
     */
    fun floorOnClear(localCleared: Long, serverClearedAt: Long, localHead: Long): Long =
        if (serverClearedAt > 0) maxOf(localCleared, serverClearedAt, 0L) else maxOf(localCleared, localHead, 0L)

    /**
     * 服务端说「已删到 [clearedAt]」的帧（`conv_update(delete)` 回声）到达时，本机会话行里位点之上已有消息
     * （[lastConvSeq] / [maxLocalSeq] 任一 > 位点）＝会话已因新消息复现，这一帧是迟到的回声：只清位点以内，**别移行**。
     * 无服务端位点（0）走本机 head 口径，一律移。
     */
    fun revivedAfter(clearedAt: Long, lastConvSeq: Long, maxLocalSeq: Long): Boolean =
        clearedAt > 0 && maxOf(lastConvSeq, maxLocalSeq) > clearedAt

    /** 有效位点 = 会话行位点与独立位点表取大（行删了表还在）。 */
    fun effective(rowFloor: Long, tableFloor: Long): Long = maxOf(rowFloor, tableFloor, 0L)

    /** 会话列表某项的 `cleared_at` 是否高过本机位点——是就要对该会话跑一遍清除例程（离线 / 重装 / 旧版遗留残留消息）。 */
    fun listNeedsClear(localCleared: Long, serverClearedAt: Long): Boolean = serverClearedAt > 0 && serverClearedAt > localCleared
}
