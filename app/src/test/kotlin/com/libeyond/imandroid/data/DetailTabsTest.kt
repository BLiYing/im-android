package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 详情页页签：只显示有内容的类别、选中项回退、横滑切签（对齐 iOS `IMChatDetailTabs` / `swipeToNextTab:`）。 */
class DetailTabsTest {

    @Test
    fun `没有任何内容时单聊一个页签都没有`() {
        assertEquals(emptyList<DetailTab>(), DetailTabs.visible(isGroup = false, nonEmpty = emptySet()))
    }

    @Test
    fun `只列有内容的类别且按固定顺序`() {
        val all = DetailTab.entries.toSet()
        assertEquals(
            listOf(DetailTab.Media, DetailTab.Files, DetailTab.Voice, DetailTab.Links, DetailTab.Contacts),
            DetailTabs.visible(isGroup = false, nonEmpty = all),
        )
        assertEquals(
            listOf(DetailTab.Files, DetailTab.Links),
            DetailTabs.visible(isGroup = false, nonEmpty = setOf(DetailTab.Links, DetailTab.Files)),
        )
    }

    @Test
    fun `群聊成员恒第一且不依赖内容`() {
        assertEquals(listOf(DetailTab.Members), DetailTabs.visible(isGroup = true, nonEmpty = emptySet()))
        assertEquals(
            listOf(DetailTab.Members, DetailTab.Voice),
            DetailTabs.visible(isGroup = true, nonEmpty = setOf(DetailTab.Voice)),
        )
    }

    @Test
    fun `选中项不在可见集合里时退到第一个`() {
        val tabs = listOf(DetailTab.Files, DetailTab.Links)
        assertEquals(DetailTab.Links, DetailTabs.resolve(tabs, DetailTab.Links))
        assertEquals(DetailTab.Files, DetailTabs.resolve(tabs, DetailTab.Media))
        assertEquals(DetailTab.Media, DetailTabs.resolve(emptyList(), DetailTab.Media))
    }

    @Test
    fun `左滑下一签右滑上一签`() {
        assertEquals(2, DetailTabs.swipeTarget(1, 4, totalDx = -200f, threshold = 100f, startedAtEdge = false))
        assertEquals(0, DetailTabs.swipeTarget(1, 4, totalDx = 200f, threshold = 100f, startedAtEdge = false))
    }

    @Test
    fun `首尾不越界且位移不够不切`() {
        assertNull(DetailTabs.swipeTarget(3, 4, -200f, 100f, false))
        assertNull(DetailTabs.swipeTarget(0, 4, 200f, 100f, false))
        assertNull(DetailTabs.swipeTarget(1, 4, -50f, 100f, false))
    }

    @Test
    fun `右滑起手在左边缘让给系统返回`() {
        assertNull(DetailTabs.swipeTarget(2, 4, 300f, 100f, startedAtEdge = true))
        // 左滑不受边缘影响
        assertEquals(3, DetailTabs.swipeTarget(2, 4, -300f, 100f, startedAtEdge = true))
    }
}
