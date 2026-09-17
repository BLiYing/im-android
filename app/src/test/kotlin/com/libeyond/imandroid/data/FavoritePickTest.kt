package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.Favorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「从收藏发送」的选择判据（对齐 iOS `IMFavoritesViewController` pick 模式）。
 * 错了都是静默的：上限不拦是一次发出几十条，取消被拦是选满后卡死，顺序按勾选先后是收端时间线乱序。
 */
class FavoritePickTest {

    private fun fav(id: Long, content: String = "hi") = Favorite(id = id, content = content, createdAt = id)

    @Test
    fun `勾选与取消勾选`() {
        assertEquals(setOf(1L), FavoritePick.toggle(emptySet(), 1))
        assertEquals(setOf(1L, 2L), FavoritePick.toggle(setOf(1L), 2))
        assertEquals(setOf(2L), FavoritePick.toggle(setOf(1L, 2L), 1))
    }

    @Test
    fun `到上限再勾被拒，但取消永远允许`() {
        val full = (1L..FavoritePick.MAX).toSet()
        assertNull(FavoritePick.toggle(full, 100))
        assertEquals(full - 3L, FavoritePick.toggle(full, 3))
        assertEquals("最多选择 9 项", FavoritePick.limitText())
    }

    @Test
    fun `发送顺序按收藏列表，不按勾选先后，空内容剔掉`() {
        val items = listOf(fav(30), fav(20, content = " "), fav(10))
        // 先勾 10 再勾 30：发出去仍是列表里的 30、10
        assertEquals(listOf(30L, 10L), FavoritePick.picked(items, linkedSetOf(10L, 20L, 30L)).map { it.id })
    }

    @Test
    fun `按钮文案与回执`() {
        assertEquals("发送", FavoritePick.sendLabel(0))
        assertEquals("发送 (3)", FavoritePick.sendLabel(3))
        assertEquals("已发送", FavoritePick.sentText(1, 0))
        assertEquals("已发送 4 条", FavoritePick.sentText(4, 0))
        assertEquals("已发送 2 条（1 条已失效未发送）", FavoritePick.sentText(2, 1))
        assertEquals("所选内容已失效，无法发送", FavoritePick.sentText(0, 2))
    }
}
