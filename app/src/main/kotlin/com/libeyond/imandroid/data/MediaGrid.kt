package com.libeyond.imandroid.data

/**
 * 归档「媒体」宫格的排布判据（详情页页签 + 会话媒体库两处共用）。
 *
 * ### 为什么需要它：宫格不能是"列表里嵌一个会自己滚的宫格"
 * 本端此前把 `LazyVerticalGrid` 塞进详情页 `LazyColumn` 的一个 `item {}` 里，
 * 并按条数算了个固定高度 `rows * 92dp`，再 `coerceAtMost(1200dp)`。两个后果都被用户报了
 * （2026-09-16）：
 *
 * 1. **纵向嵌套同向滚动**：手指落在宫格上时竖向拖动被内层吃掉，外层详情页滚不动
 *    ——表现就是"这个页面很卡 / 划不动"，而不是任何一种报错。
 * 2. **1200dp 封顶把内容裁掉**：媒体一多（约 13 行以上），超出的那些格子被切在容器外，
 *    永远看不到，连滚都滚不到——"看不到媒体里全部照片"。
 *
 * 正解是**宫格本身就由外层列表逐行渲染**：一行一个 `item`，没有内层滚动容器，
 * 也就没有高度要算、没有上限要夹，同时天然是惰性的（只组合可见行）。
 * 这个对象只负责"怎么分行、最后一行缺几格"这一点纯判断。
 */
object MediaGrid {

    /**
     * 每行几格。**3 列**，与 iOS 两处宫格逐字一致：`IMConversationMediaViewController`
     * （`cols = 3, spacing = 2`）与详情页的 `IMDetailMediaContainerCell.tileForWidth:`
     * （`floor((width - (cols-1)*sp) / 3)`，正方格）。
     *
     * 此前写的是 4，注释还声称"与 iOS 同"——**是错的**，格子比 iOS 小一圈（2026-09-17 对照源码核出来的）。
     */
    const val COLUMNS = 3

    /** 按 [columns] 把条目切成一行一行。空集回空表（调用方据此走空态，不画空行）。 */
    fun <T> rows(items: List<T>, columns: Int = COLUMNS): List<List<T>> =
        if (columns <= 0) emptyList() else items.chunked(columns)

    /**
     * 最后一行还缺几格。
     *
     * **必须用等宽空位补齐，不能让最后一行自己撑开**：每格的宽是 `weight(1f)` 分出来的，
     * 只有两张图的末行不补位的话，那两张会各占半屏——同一个宫格里格子大小不一样，
     * 看起来像排版坏了。
     */
    fun blanksInLastRow(count: Int, columns: Int = COLUMNS): Int {
        if (columns <= 0 || count <= 0) return 0
        val rest = count % columns
        return if (rest == 0) 0 else columns - rest
    }
}
