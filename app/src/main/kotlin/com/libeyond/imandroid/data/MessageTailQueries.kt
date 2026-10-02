package com.libeyond.imandroid.data

/** [MessageRepository.tailState] 的结果：做「要不要问服务端 / 尾窗从哪起」决定要的全部输入。 */
data class TailState(
    /** 会话最新位点（未知 0）。 */
    val tip: Long,
    /** 有效可见起点（含）；0 = 不设。 */
    val visibleFrom: Long,
    /** 最新一页是否被同一段区间完整覆盖。 */
    val covered: Boolean,
    /** 包含 [tip] 的那一段区间的 `lo`（尾窗下界）；`tip` 不在任何段内或未知 → 0（不设下界）。 */
    val segmentLo: Long,
)

/**
 * 读出「最新一页」的现状（C4）。只读，不发任何请求。
 *
 * 尾窗下界 [TailState.segmentLo] 与 [visibleFrom] 取大：清空过的会话，尾窗不该把位点以下的东西露出来。
 */
suspend fun MessageRepository.tailState(owner: String, convId: String, page: Int): TailState {
    val c = conversations.byId(owner, convId)
    val tip = ChatTailPlan.tip(c?.headConvSeq ?: 0L, c?.lastConvSeq ?: 0L)
    val from = ChatTailPlan.visibleFrom(c?.clearedUpTo ?: 0L)
    val rs = ranges.ranges(owner, convId)
    val lo = ChatTailPlan.latestPageLowAboveFloor(tip, page, from)
    val covered = tip > 0 && (tip < from || SyncRanges.coversSpan(rs, lo, tip))
    val segLo = if (tip > 0) SyncRanges.rangeContaining(rs, tip)?.lo ?: 0L else 0L
    return TailState(tip, from, covered, maxOf(segLo, from))
}
