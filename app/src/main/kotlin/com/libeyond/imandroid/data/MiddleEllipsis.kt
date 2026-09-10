package com.libeyond.imandroid.data

/**
 * 中间截断：放不下时变成「开头…结尾」（CHAT_UI_SKETCH §5 文件名，iOS `NSLineBreakByTruncatingMiddle`）。
 *
 * 文件名必须保住结尾——`季度报表-最终版-改3.xlsx` 截成 `季度报表-最终版-…` 就看不出是表格还是文档。
 * 当前 Compose（foundation 1.7）没有 `TextOverflow.MiddleEllipsis`，只能自己二分。
 * 「放不放得下」由 UI 层用 TextMeasurer 回答，这里只管搜索，因此可以 JVM 单测。
 */
object MiddleEllipsis {

    /** [fits] 需对保留字符数单调（留得越多越放不下）。一个字都放不下时返回「…」。 */
    fun fit(text: String, fits: (String) -> Boolean): String {
        if (fits(text)) return text
        val cs = graphemeClusters(text)
        var lo = 0
        var hi = cs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(candidate(cs, mid))) lo = mid else hi = mid - 1
        }
        return candidate(cs, lo)
    }

    /** 保留 [keep] 个字符簇：前一半在「…」前，后一半在后（奇数时前面多一个）。 */
    private fun candidate(cs: List<String>, keep: Int): String {
        val tail = keep / 2
        val head = keep - tail
        return cs.take(head).joinToString("") + "…" + cs.takeLast(tail).joinToString("")
    }
}
