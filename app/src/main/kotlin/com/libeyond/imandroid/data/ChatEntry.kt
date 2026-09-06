package com.libeyond.imandroid.data

/**
 * 进会话停在哪里（`../IMServer/docs/CHAT_UX.md` §3）——
 * 与 iOS `IMChatEntryHasUnread`/`IMChatEntryWindowAnchor`、Web `entryWindow.ts` **同一份口径**。
 * 这是 `SYMMETRY.md` 登记在册的对称路径，改判据必须三端同时改。
 *
 * 抽成纯函数的理由：**它错了不会报错**，只是首屏停在了不该停的地方。
 */
object ChatEntry {

    /** 未读分割线上方保留的已读上下文条数。 */
    const val CONTEXT_BEFORE = 3

    /**
     * 是否按「有未读」处理。
     *
     * **只认服务端算出的真实未读数 [unread]**。
     *
     * 绝不能写成 `latestSeq > readSeq`——那对**发送方必然成立**：服务端的未读计数
     * 排除本人消息（`sender <> ?`），所以自己刚发的一万条不会推进自己的读位点。
     * Web 2026-09-03 就是这么栽的：压测灌完后本人进会话被锚到一万条之前，
     * 不贴底、↓N 显示一大串，看着像"消息没发出去"。
     */
    fun hasUnread(unread: Int): Boolean = unread > 0

    /**
     * 首屏滚到哪一行。
     *
     * @param rowSeqs 各行的 conv_seq，按显示序排列；非消息行（日期胶囊/待发）传 0
     * @param readSeq 本人已读位点
     * @param unread **真实未读数**
     * @return 目标行下标；无未读时返回最后一行（贴底）
     */
    fun entryScrollIndex(rowSeqs: List<Long>, readSeq: Long, unread: Int): Int {
        if (rowSeqs.isEmpty()) return 0
        if (!hasUnread(unread)) return rowSeqs.lastIndex

        // 首条未读 = 第一条 conv_seq > readSeq 的消息行
        val firstUnread = rowSeqs.indexOfFirst { it > 0 && it > readSeq }
        if (firstUnread < 0) return rowSeqs.lastIndex
        // 往上多带一点上下文，让分割线不贴着屏幕顶
        return (firstUnread - CONTEXT_BEFORE).coerceAtLeast(0)
    }

    /**
     * 未读分割线插在哪一行**之前**；-1 表示不画。
     *
     * 只在真有未读、且能定位到首条未读时画。
     */
    fun unreadDividerIndex(rowSeqs: List<Long>, readSeq: Long, unread: Int): Int {
        if (!hasUnread(unread)) return -1
        val idx = rowSeqs.indexOfFirst { it > 0 && it > readSeq }
        return idx
    }

    /**
     * 新内容到达后要不要自动贴底。
     *
     * **只在用户本来就贴着底时才滚**——离底较远说明他正在翻历史，
     * 把他拽回底部是最招人烦的一种"贴心"。
     *
     * @param lastVisibleIndex 当前可见的最后一行下标；列表还没测量时传 -1
     * @param totalRows 总行数
     */
    fun shouldAutoScroll(lastVisibleIndex: Int, totalRows: Int): Boolean {
        if (totalRows <= 0) return false
        // 还没测量过（首次组合）不算"贴底"——首屏定位由 entryScrollIndex 负责，
        // 两条路各管各的，混在一起会让「进会话停在首条未读」被自动贴底当场覆盖掉。
        if (lastVisibleIndex < 0) return false
        return lastVisibleIndex >= totalRows - 1 - NEAR_BOTTOM_SLACK
    }

    /** 距底多少行以内算「贴着底」。 */
    const val NEAR_BOTTOM_SLACK = 2
}
