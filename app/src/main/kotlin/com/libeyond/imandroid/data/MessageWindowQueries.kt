package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow

// 聊天窗口与会话内搜索的**读查询**。从 MessageRepository 搬出来只为控体量（那边贴着 600 行硬闸）——
// 写成扩展函数是刻意的：调用点一个字没改（仍是 `repo.windowAround(...)`），
// 这样这次搬家不会在别处留下"改了一半"的痕迹。

/**
 * 观察一个**渲染窗口**（[ChatWindow]）。尾窗 = 最近 N 条（新消息会进来），
 * 锚点窗 = 一段闭区间（新消息不进来，用户正在看历史）。
 */
fun MessageRepository.observeWindow(owner: String, convId: String, window: ChatWindow): Flow<List<MessageEntity>> =
    when (window) {
        is ChatWindow.Tail ->
            if (window.fromSeq > 0) observeTail(owner, convId, window.fromSeq, window.limit)
            else observeMessages(owner, convId, window.limit)
        is ChatWindow.Anchored ->
            messages.observeRange(owner, convId, window.loTs, window.loSeq, window.hiTs, window.hiSeq)
    }

/**
 * 围绕 `convSeq` 那条消息开一个锚点窗（本地库）。回 `null` = **本地没有这一条**，
 * 调用方据此决定是问服务端（`window_req`）还是如实说它不在了。
 */
suspend fun MessageRepository.windowAround(
    owner: String,
    convId: String,
    convSeq: Long,
    half: Int = ChatWindows.ANCHOR_HALF,
): ChatWindow.Anchored? {
    val m = messages.byConvSeq(owner, convId, convSeq) ?: return null
    // **只取目标所在的那一段**：本地有缺口时，「前后各一页」不能把缺口另一侧的旧岛拼进来（C3，段内取）；
    // 本机清空位点以下的也不要
    val seg = SyncRanges.rangeContaining(ranges.ranges(owner, convId), m.convSeq)
    val from = maxOf(seg?.lo ?: 0L, ChatTailPlan.visibleFrom(conversations.byId(owner, convId)?.clearedUpTo ?: 0L))
    return ChatWindows.boundsOf(
        before = messages.pointsBefore(owner, convId, m.timestamp, m.convSeq, half).filter { it.convSeq >= from },
        atOrAfter = messages.pointsAtOrAfter(owner, convId, m.timestamp, m.convSeq, half)
            .filter { seg == null || it.convSeq <= seg.hi },
    )
}

/**
 * 把锚点窗的**下界**再往前挪一页（向上翻页）。没有更早的了就原样返回——
 * 调用方据此知道"到头了"，不必再问。
 */
suspend fun MessageRepository.extendWindowOlder(
    owner: String,
    convId: String,
    window: ChatWindow.Anchored,
    page: Int = ChatWindows.ANCHOR_PAGE,
    /** 服务端可见下界（[HistoryFloors]）。 */
    historyFloor: Long = 0L,
): ChatWindow.Anchored {
    // 同上：只在窗口下沿所在的那一段里往上取，不跨缺口、不越过可见起点；取不到就原样返回，调用方据此去问服务端
    val seg = SyncRanges.rangeContaining(ranges.ranges(owner, convId), window.loSeq)
    val from = maxOf(seg?.lo ?: 0L, ChatTailPlan.visibleFrom(conversations.byId(owner, convId)?.clearedUpTo ?: 0L, historyFloor))
    val older = messages.pointsBefore(owner, convId, window.loTs, window.loSeq, page).filter { it.convSeq >= from }
    val lo = older.lastOrNull() ?: return window
    return window.copy(loTs = lo.timestamp, loSeq = lo.convSeq)
}

/**
 * 把锚点窗的**上界**再往后挪一页（向下翻页，对称 [extendWindowOlder]）。窗口上沿所在那一段里没有更新的了就原样返回——
 * 调用方据此判断「段内到头了」：是 tip 就是真到头，不是 tip 则向服务端要一页再试。
 */
suspend fun MessageRepository.extendWindowNewer(
    owner: String,
    convId: String,
    window: ChatWindow.Anchored,
    page: Int = ChatWindows.ANCHOR_PAGE,
): ChatWindow.Anchored {
    val seg = SyncRanges.rangeContaining(ranges.ranges(owner, convId), window.hiSeq)
    // 含窗口上沿自己，所以先丢掉第一个；不跨缺口（超出本段上沿的不要）
    val newer = messages.pointsAtOrAfter(owner, convId, window.hiTs, window.hiSeq, page + 1)
        .drop(1).filter { seg == null || it.convSeq <= seg.hi }
    val hi = newer.lastOrNull() ?: return window
    return window.copy(hiTs = hi.timestamp, hiSeq = hi.convSeq)
}

/**
 * 会话内搜索（本地库，整个会话）。返回**显示序倒序**（新在前）的命中，最多 [limit] 条。
 *
 * DAO 那条 SQL 负责收窄，[ChatSearch.matches] 是权威判定——两层的理由见它的注释。
 * `keyword` 由调用方 trim；空词回空集——**除非带着 [fromUid]**：「来自某人」过滤单独成立时
 * 不要求关键词（对齐 iOS `searchState.searchFromUID` 与关键词是"与非必填"的关系）。
 */
suspend fun MessageRepository.searchMessages(
    owner: String,
    convId: String,
    keyword: String,
    fromUid: String = "",
    limit: Int = ChatSearch.LOCAL_PAGE_LIMIT,
): LocalSearchPage {
    val needle = keyword.trim()
    if (needle.isEmpty() && fromUid.isEmpty()) return LocalSearchPage(emptyList(), truncated = false)
    val like = if (needle.isEmpty()) "" else "%" + ChatSearch.escapeLike(needle) + "%"
    val raw = messages.search(owner, convId, like, fromUid, limit)
    // 复核过滤只在**有关键词**时跑——[ChatSearch.matches] 对空词恒回 false（它是关键词命中判据，
    // 不是发件人判据），纯「来自」过滤没有关键词可复核，SQL 的 `sender = :fromUid` 就是权威判据。
    val rows = if (needle.isEmpty()) {
        raw
    } else {
        val lowered = needle.lowercase()
        raw.filter { ChatSearch.matches(it.contentType, it.content, it.caption, it.fileName, lowered) }
    }
    // **截断与否要看 SQL 取回多少条，不是过滤后剩多少**：只要复核过滤掉一条，
    // 过滤后的长度就够不到 limit，「还有更多」那个 `+` 会静默消失
    // ——正是 hitLabel 那条"不能悄悄显示成总共就这些"要防的事。
    return LocalSearchPage(rows = rows, truncated = raw.size >= limit)
}

/**
 * 「来自」候选发件人 uid 列表（本会话已发过消息的去重集合，见 [com.libeyond.imandroid.data.db.MessageDao.distinctSenders]）。
 * 只回 uid——名字/头像由调用方按本机显示名口径（备注 > 群昵称 > 昵称）现解析，这里不掺进来。
 */
suspend fun MessageRepository.distinctSenders(owner: String, convId: String): List<String> =
    messages.distinctSenders(owner, convId)

/**
 * 从某个时间点起（含）本地库里第一条可见消息的 conv_seq；没有则 null。
 *
 * 日历「跳到某天」与「今天」共用这一条：不专门按天分桶——第一条 ≥ 目标时间点的消息，
 * 落在目标那天就是那天的第一条，那天没有消息就自然落到下一个有消息的日子，
 * 一条查询同时覆盖"精确落点"与"退到下一个有消息的日子"两种情形，不必分两步。
 *
 * **仅在本地完整时可信**：有缺口时目标那天的消息可能整段在缺口里，这条查询会跳过缺口
 * 静默落到缺口之后的某条——那是错的。有缺口时改问服务端日历接口（`ConversationsApi.calendar`）。
 */
suspend fun MessageRepository.firstConvSeqAtOrAfter(owner: String, convId: String, fromMs: Long): Long? =
    messages.firstConvSeqAtOrAfter(owner, convId, fromMs)

/** [firstConvSeqAtOrAfter] 的当天版：只在 `[dayStartMs, dayStartMs + 1 天)` 内找（有缺口又没有服务端日历时用）。 */
suspend fun MessageRepository.firstConvSeqOnDay(owner: String, convId: String, dayStartMs: Long): Long? =
    messages.firstConvSeqBetween(owner, convId, dayStartMs, dayStartMs + ChatCalendar.DAY_MS)

/**
 * 日历弹层打点集合（本地时区分桶 ms，见 [com.libeyond.imandroid.data.db.MessageDao.activeLocalDayStarts]）。
 * 本地完整与否都查——离线/有缺口时至少能画出本地已下载部分的点，不是"没有点"。
 */
suspend fun MessageRepository.activeLocalDayStarts(owner: String, convId: String, utcOffsetMs: Long): Set<Long> =
    messages.activeLocalDayStarts(owner, convId, utcOffsetMs).toSet()

/**
 * 本地一页搜索结果。
 *
 * 单独一个类型只为带上 [truncated]：命中被单页上限截断时计数要补 `+`，
 * 而这件事只有**查询层**知道（UI 拿到的是复核过滤之后的列表，长度反推不出来）。
 * 与 [searchMessages] 同住一个文件——它是这个类型唯一的产地。
 */
data class LocalSearchPage(
    val rows: List<MessageEntity>,
    val truncated: Boolean,
)

/** 本地这个会话齐不齐（[ChatSearch.isComplete] 的取数版本）。[historyFloor] 取 `client.messages.historyFloors.get(convId)`。 */
suspend fun MessageRepository.isLocalComplete(owner: String, convId: String, historyFloor: Long = 0L): Boolean {
    val c = conversations.byId(owner, convId)
    return ChatSearch.isComplete(
        ranges = ranges.ranges(owner, convId),
        tip = ChatTailPlan.tip(c?.headConvSeq ?: 0L, c?.lastConvSeq ?: 0L),
        visibleFrom = ChatTailPlan.visibleFrom(c?.clearedUpTo ?: 0L, historyFloor),
    )
}

/** 本机清空位点（设计 §6.7）；服务端的搜索 / 日历 / 媒体结果要用它滤掉位点以内的条目。没有会话行 = 0。 */
suspend fun MessageRepository.clearedUpTo(owner: String, convId: String): Long =
    conversations.byId(owner, convId)?.clearedUpTo ?: 0L
