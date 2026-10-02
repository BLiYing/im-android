package com.libeyond.imandroid.data

/**
 * 会话内搜索「服务端命中翻页」的纯逻辑（设计 §4.9 第 1 项后半；对称 Web `searchPaging.ts`、iOS `IMChatSearchPaging`）。
 *
 * 服务端按 conv_seq **倒序**一页页给（cursor = 上一页的 `next_cursor`），命中集在三端都按**升序**用
 * （下标 0 = 最早，▲ = 更旧）。所以「再要一页」拿回来的是**更旧**的一批，要拼到命中集**前面**——
 * 当前下标随之整体后移，这是最容易算错、算错了又不报错的地方（表现是 ▲ 一下跳到了别的命中上）。
 */
object SearchPaging {

    /**
     * 服务端可能回一页 0 条却仍 has_more：它先取 limit+1 条再按「仅为我删除」等逐人隐藏过滤，
     * 隐藏项多时单页展示数会少于 limit。一次 ▲ 里连续遇到空页时最多再往前翻这么多页——
     * 满屏隐藏项的会话不能把一次点击变成无界请求。
     */
    const val MAX_EMPTY_PAGES = 5

    /** [hits] 新命中集（升序）、[added] 真正新增的条数（调用方把下标落到 `added - 1`，紧挨原最旧命中）。 */
    data class Merged(val hits: List<SearchHit>, val added: Int)

    /**
     * 把更旧的一页并到升序命中集前面。[page] 顺序不限。
     * 只收比当前最旧命中**还旧**的项：防重复页 / 游标回退把已有命中再塞一遍（计数虚涨、下标错位）。
     */
    fun prependOlder(current: List<SearchHit>, page: List<SearchHit>): Merged {
        val oldest = current.firstOrNull()?.convSeq ?: Long.MAX_VALUE
        val older = page.filter { it.convSeq in 1 until oldest }
            .associateBy { it.convSeq }.values.sortedBy { it.convSeq }
        return Merged(older + current, older.size)
    }
}
