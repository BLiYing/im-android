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
        is ChatWindow.Tail -> observeMessages(owner, convId, window.limit)
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
    return ChatWindows.boundsOf(
        before = messages.pointsBefore(owner, convId, m.timestamp, m.convSeq, half),
        atOrAfter = messages.pointsAtOrAfter(owner, convId, m.timestamp, m.convSeq, half),
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
): ChatWindow.Anchored {
    val older = messages.pointsBefore(owner, convId, window.loTs, window.loSeq, page)
    val lo = older.lastOrNull() ?: return window
    return window.copy(loTs = lo.timestamp, loSeq = lo.convSeq)
}

/**
 * 会话内搜索（本地库，整个会话）。返回**显示序倒序**（新在前）的命中，最多 [limit] 条。
 *
 * DAO 那条 SQL 负责收窄，[ChatSearch.matches] 是权威判定——两层的理由见它的注释。
 * `keyword` 由调用方 trim；空词回空集（与后端 G4 一致：不报错，便于清空搜索框时复用同一条路）。
 */
suspend fun MessageRepository.searchMessages(
    owner: String,
    convId: String,
    keyword: String,
    limit: Int = ChatSearch.LOCAL_PAGE_LIMIT,
): LocalSearchPage {
    val needle = keyword.trim()
    if (needle.isEmpty()) return LocalSearchPage(emptyList(), truncated = false)
    val like = "%" + ChatSearch.escapeLike(needle) + "%"
    val lowered = needle.lowercase()
    val raw = messages.search(owner, convId, like, limit)
    // **截断与否要看 SQL 取回多少条，不是过滤后剩多少**：只要复核过滤掉一条，
    // 过滤后的长度就够不到 limit，「还有更多」那个 `+` 会静默消失
    // ——正是 hitLabel 那条"不能悄悄显示成总共就这些"要防的事。
    return LocalSearchPage(
        rows = raw.filter { ChatSearch.matches(it.contentType, it.content, it.caption, it.fileName, lowered) },
        truncated = raw.size >= limit,
    )
}

/** 本地这个会话齐不齐（[ChatSearch.isLocalComplete] 的取数版本）。 */
suspend fun MessageRepository.isLocalComplete(owner: String, convId: String): Boolean {
    val row = conversations.byId(owner, convId)
    return ChatSearch.isLocalComplete(row?.syncedConvSeq ?: 0L, row?.lastConvSeq ?: 0L)
}
