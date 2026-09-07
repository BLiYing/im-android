package com.libeyond.mediapicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMergeTest {

    private fun a(id: Long) = MediaAsset(id, "u$id", "image/jpeg", "n$id", 100, id, "b", "B")

    @Test
    fun `重复的条目不会被追加两次`() {
        // 分页期间用户拍了张新照片 → 列表整体挪位 → 同一条同时出现在两页里。
        // 不去重的话 LazyVerticalGrid 的 key 重复，当场抛。
        val cur = listOf(a(3), a(2), a(1))
        val r = PageMerge.merge(cur, listOf(a(1), a(0)), pageSize = 3)
        assertEquals(listOf(3L, 2L, 1L, 0L), r.assets.map { it.id })
    }

    @Test
    fun `不满一页就是到底了`() {
        assertTrue(PageMerge.merge(emptyList(), listOf(a(1), a(2)), pageSize = 3).exhausted)
        assertTrue(PageMerge.merge(emptyList(), emptyList(), pageSize = 3).exhausted)
        assertFalse(PageMerge.merge(emptyList(), listOf(a(1), a(2), a(3)), pageSize = 3).exhausted)
    }

    @Test
    fun `满页全是重复也不能判定到底`() {
        // 列表整体挪位时这一页可能全是已知条目——但它是满的，后面还有。
        // 按「去重后为 0」判到底的话，用户就再也翻不下去了。
        val cur = listOf(a(3), a(2), a(1))
        val r = PageMerge.merge(cur, listOf(a(3), a(2), a(1)), pageSize = 3)
        assertEquals(3, r.assets.size)
        assertFalse(r.exhausted)
    }
}
