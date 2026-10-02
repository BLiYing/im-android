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
    /** [tip] 是否落在某一段区间内：窗口只有含 tip 才配叫「尾窗」（新消息才该继续进来）。 */
    val tipInRange: Boolean,
)

/**
 * 读出「最新一页」的现状（C4）。只读，不发任何请求。
 *
 * 尾窗下界 [TailState.segmentLo] 与 [visibleFrom] 取大：清空过的会话，尾窗不该把位点以下的东西露出来。
 */
suspend fun MessageRepository.tailState(owner: String, convId: String, page: Int, historyFloor: Long = 0L): TailState {
    val c = conversations.byId(owner, convId)
    val tip = ChatTailPlan.tip(c?.headConvSeq ?: 0L, c?.lastConvSeq ?: 0L)
    val from = ChatTailPlan.visibleFrom(c?.clearedUpTo ?: 0L, historyFloor)
    val rs = ranges.ranges(owner, convId)
    val lo = ChatTailPlan.latestPageLowAboveFloor(tip, page, from)
    val covered = tip > 0 && (tip < from || SyncRanges.coversSpan(rs, lo, tip))
    val seg = if (tip > 0) SyncRanges.rangeContaining(rs, tip) else null
    return TailState(tip, from, covered, maxOf(seg?.lo ?: 0L, from), tipInRange = seg != null)
}

/**
 * 进会话的取数分流（C3）：读出 tip / 区间清单 / 本地最新 / 有效下界，交给纯函数 [ChatEntryPlan.plan]。
 * 只读，不发任何请求。
 */
suspend fun MessageRepository.planEntry(
    owner: String,
    convId: String,
    readSeq: Long,
    unread: Int,
    historyFloor: Long = 0L,
): EntryPlan {
    val c = conversations.byId(owner, convId)
    val tip = ChatTailPlan.tip(c?.headConvSeq ?: 0L, c?.lastConvSeq ?: 0L)
    val visibleFrom = ChatTailPlan.visibleFrom(c?.clearedUpTo ?: 0L, historyFloor)
    return ChatEntryPlan.plan(
        readSeq = readSeq, unread = unread, tip = tip,
        ranges = ranges.ranges(owner, convId),
        localNewest = messages.maxConvSeq(owner, convId) ?: 0L,
        floor = maxOf(0L, visibleFrom - 1),
    )
}

/**
 * 进会话时**本地**怎么开窗（本地已齐，或服务端那一窗已落库之后）：
 * - 无未读 → 尾窗，下界固定在最新那一段（不拼缺口另一侧的旧岛）；
 * - 有未读且首条未读就在尾窗里（读位点之后的本地条数 + 上下文放得进一窗）→ 仍是尾窗——**新消息要能继续进来**
 *   （锚点窗不收新消息，那是「正在看历史」的语义；普通会话几条未读不该因此收不到实时消息）；
 * - 有未读且首条未读在尾窗之外（积压很多）→ 围着读位点开锚点窗，只取读位点所在那一段。
 *
 * 回 `null` = **本地没有任何可以安全开的窗**（有未读、读位点附近一条都没有——服务端那一窗没取到）。
 * **不拿尾窗兜底**：本地最新 200 条可能是离线期间实时进来、与读位点隔着缺口的行，"可见即读"一上报读位点就越过缺口，
 * 未读被悄悄清掉（iOS 2026-09-03 十万未读打开即清零的降级版本）。调用方应保持空窗并如实说「需要联网加载」。
 */
suspend fun MessageRepository.localEntryWindow(
    owner: String,
    convId: String,
    readSeq: Long,
    unread: Int,
    historyFloor: Long = 0L,
): ChatWindow? {
    val st = tailState(owner, convId, ChatWindows.LATEST_FETCH, historyFloor)
    val tail = ChatWindow.Tail(ChatWindows.TAIL_LIMIT, st.segmentLo)
    if (!ChatEntryPlan.hasUnread(unread)) return tail
    // 尾窗只在两个条件**同时**成立时才配用（新消息才该继续进来，且「可见即读」不会越过缺口）：
    //  ① 读位点之后到 tip 之间**没有缺口**（tip 所在那一段从读位点之后就开始）——离线期间实时进来的最新一条是个孤岛，
    //     与读位点隔着缺口，硬当尾窗用，「可见即读」一上报读位点就越过缺口，十万未读被悄悄清掉；
    //  ② 首条未读放得进一窗：**用服务端真实未读数**（与本地读位点之后的条数取大），不能只数本地——本地可能只有那一个孤岛。
    val after = messages.countAfter(owner, convId, readSeq)
    val seg = if (st.tip > 0) SyncRanges.rangeContaining(ranges.ranges(owner, convId), st.tip) else null
    val gapFree = st.tip <= 0 || st.tip < st.visibleFrom || (seg != null && seg.lo <= maxOf(readSeq + 1, st.visibleFrom))
    val fits = maxOf(unread, after) + ChatWindows.ENTRY_BEFORE <= ChatWindows.TAIL_LIMIT
    if (gapFree && fits) return tail
    return anchoredAroundReadSeq(owner, convId, readSeq, st) ?: if (gapFree) tail else null // 尾窗能装下首条未读却没找到那一条（全是事件行）：本地尾窗可用
}

/** 围着「读位点之后第一条」开锚点窗，只取它所在的那一段、不越过可见起点。本地没有那一条返回 null。 */
private suspend fun MessageRepository.anchoredAroundReadSeq(
    owner: String,
    convId: String,
    readSeq: Long,
    st: TailState,
): ChatWindow.Anchored? {
    // 读位点落在可见起点以下（清空位点之后 / 服务端下界之下）时，从可见起点算起——别把被清掉的行当成「读位点之后第一条」
    val first = messages.nextAfterSeq(owner, convId, maxOf(readSeq, st.visibleFrom - 1)) ?: return null
    val seg = SyncRanges.rangeContaining(ranges.ranges(owner, convId), first.convSeq)
    // 那一条必须和读位点**接得上**（它所在那一段从读位点之后就开始）：否则它只是缺口另一侧的孤岛，
    // 拿它当「读位点之后第一条」会把用户定位到错的地方，可见即读还会越过缺口
    if (seg == null || seg.lo > maxOf(readSeq + 1, st.visibleFrom)) return null
    val lowBound = maxOf(seg?.lo ?: 0L, st.visibleFrom)
    val before = messages.pointsBefore(owner, convId, first.timestamp, first.convSeq, ChatWindows.ENTRY_BEFORE)
        .filter { it.convSeq >= lowBound }
    val atOrAfter = messages.pointsAtOrAfter(owner, convId, first.timestamp, first.convSeq, ChatWindows.ENTRY_AFTER)
        .filter { seg == null || it.convSeq <= seg.hi }
    return ChatWindows.boundsOf(before, atOrAfter)
}

/** ↓N 取数要的本地事实（[UnreadBelow.count] 的输入里「端上读库」的那部分）。 */
data class UnreadBelowFacts(val tip: Long, val floor: Long, val ranges: List<SeqRange>, val localNewest: Long)

suspend fun MessageRepository.unreadBelowFacts(owner: String, convId: String, historyFloor: Long): UnreadBelowFacts {
    val c = conversations.byId(owner, convId)
    return UnreadBelowFacts(
        tip = ChatTailPlan.tip(c?.headConvSeq ?: 0L, c?.lastConvSeq ?: 0L),
        floor = maxOf(0L, ChatTailPlan.visibleFrom(c?.clearedUpTo ?: 0L, historyFloor) - 1),
        ranges = ranges.ranges(owner, convId),
        localNewest = messages.maxConvSeq(owner, convId) ?: 0L,
    )
}
