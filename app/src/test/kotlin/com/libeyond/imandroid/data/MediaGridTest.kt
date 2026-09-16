package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档「媒体」宫格的分行判据（[MediaGrid]）。
 *
 * 这一组钉的是 2026-09-16 用户报的两条：详情页划不动、看不到全部照片。
 * 根因是宫格当初被塞进 `LazyColumn` 的一个 `item {}` 里、还按条数算了个封顶 1200dp 的固定高度
 * ——纵向嵌套同向滚动 + 超出部分被裁掉。改成由外层列表逐行渲染之后，
 * "怎么分行、末行缺几格"就是这里这点判断。
 */
class MediaGridTest {

    @Test
    fun `按列数分行`() {
        val rows = MediaGrid.rows((1..9).toList(), columns = 4)
        assertEquals(3, rows.size)
        assertEquals(listOf(1, 2, 3, 4), rows[0])
        assertEquals(listOf(9), rows[2])
    }

    @Test
    fun `空集合回空表——调用方据此走空态，不画空行`() {
        assertTrue(MediaGrid.rows(emptyList<Int>()).isEmpty())
    }

    // 末行不补位的话，只有两张图的那一行会各占半屏——同一个宫格里格子大小不一
    @Test
    fun `末行缺几格`() {
        assertEquals(0, MediaGrid.blanksInLastRow(8, columns = 4))
        assertEquals(1, MediaGrid.blanksInLastRow(7, columns = 4))
        assertEquals(3, MediaGrid.blanksInLastRow(5, columns = 4))
        assertEquals(0, MediaGrid.blanksInLastRow(0, columns = 4))
    }

    @Test
    fun `条数不足一行时也要补满`() {
        assertEquals(listOf(listOf(1, 2)), MediaGrid.rows(listOf(1, 2), columns = 4))
        assertEquals(2, MediaGrid.blanksInLastRow(2, columns = 4))
    }

    /**
     * **默认列数必须是 3**，与 iOS 两处宫格逐字一致（`IMConversationMediaViewController` 的
     * `cols = 3`、详情页 `IMDetailMediaContainerCell.tileForWidth:`）。
     *
     * 上面几条都显式传了 `columns = 4`，所以**没有一条钉得住默认值**——本端的默认值一直是 4、
     * 注释还写着"与 iOS 同"，2026-09-17 对照源码才发现是错的。这条就是补那个洞。
     */
    @Test
    fun `默认列数与 iOS 同为 3`() {
        assertEquals(3, MediaGrid.COLUMNS)
        assertEquals(listOf(listOf(1, 2, 3), listOf(4)), MediaGrid.rows(listOf(1, 2, 3, 4)))
        assertEquals(2, MediaGrid.blanksInLastRow(4))
    }
}
