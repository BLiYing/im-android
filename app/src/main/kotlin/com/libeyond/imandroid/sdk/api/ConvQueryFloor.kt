package com.libeyond.imandroid.sdk.api

/**
 * 服务端三个「整会话」查询（搜索 / 日历 / 媒体库）的**本机清空位点**过滤（设计 §6.7，对称 Web `convQueriesApi.ts`
 * 的 `dropClearedItems` / `hasMoreAboveFloor` / `dropClearedDays`）。
 *
 * 服务端还留着用户在本机清空掉的消息：不滤的话搜得到刚清掉的内容、日历给已清空的日子打点、媒体库里翻得出已清掉的图。
 * 位点为 `0` 原样返回。纯函数，单测在 `ConvQueryFloorTest`。
 */
object ConvQueryFloor {

    /**
     * 服务端还能不能翻出位点之上的东西：游标是「上一页最后一条的 conv_seq」，之后的页只会更小。
     * 游标已落到 `位点 + 1` 及以下时，剩下的全在位点以内，别再翻了（否则清空过的大会话要空翻一串页）。
     */
    fun hasMoreAboveFloor(hasMore: Boolean, nextCursor: Long, clearedUpTo: Long): Boolean =
        hasMore && nextCursor > 0 && nextCursor > clearedUpTo + 1

    /** 搜索的 `has_more` 无条件要求 `next_cursor > 0`（同 Web）：游标为 0 再拿去翻会回到最新页，▲ 永不停。 */
    fun search(page: ConvSearchPage, clearedUpTo: Long): ConvSearchPage = page.copy(
        items = if (clearedUpTo > 0) page.items.filter { it.convSeq > clearedUpTo } else page.items,
        hasMore = hasMoreAboveFloor(page.hasMore, page.nextCursor, maxOf(0L, clearedUpTo)),
    )

    fun media(page: ConvMediaPage, clearedUpTo: Long): ConvMediaPage =
        if (clearedUpTo <= 0) page else page.copy(
            items = page.items.filter { it.convSeq > clearedUpTo },
            hasMore = hasMoreAboveFloor(page.hasMore, page.nextCursor, clearedUpTo),
        )

    /** 向更新方向的一页：只丢位点以内的项；`has_more` / 游标不动（向新翻不会碰到位点之下）。 */
    fun mediaNewer(page: ConvMediaPage, clearedUpTo: Long): ConvMediaPage =
        if (clearedUpTo <= 0) page else page.copy(items = page.items.filter { it.convSeq > clearedUpTo })

    /** 「当天第一条」在位点以内的日子，服务端计数里掺着已清掉的消息——整天丢掉（位点之后本机新收的消息由本地打点补上）。 */
    fun calendar(res: ConvCalendarResult, clearedUpTo: Long): ConvCalendarResult =
        if (clearedUpTo <= 0) res else res.copy(days = res.days.filter { it.firstConvSeq > clearedUpTo })
}
