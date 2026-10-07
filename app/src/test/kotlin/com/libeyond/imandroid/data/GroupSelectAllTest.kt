package com.libeyond.imandroid.data

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class GroupSelectAllTest {
    private val all = listOf("a", "b", "c", "d")

    @Test fun `no search selects everything`() {
        assertEquals(setOf("a", "b", "c", "d"), GroupSelectAll.next(emptySet(), all, 0))
    }

    @Test fun `search selects only visible`() {
        assertEquals(setOf("a"), GroupSelectAll.next(emptySet(), listOf("a"), 0))
    }

    @Test fun `existing selection is kept`() {
        val r = GroupSelectAll.next(setOf("d", "x"), listOf("a", "b"), 0)
        assertEquals(setOf("d", "x", "a", "b"), r)
    }

    @Test fun `limit truncates in visible order and keeps selected`() {
        val r = GroupSelectAll.next(setOf("c"), all, 3)
        assertEquals(listOf("c", "a", "b"), r.toList())
        // 已选满上限、可见行里没有已选：不补也不清
        assertEquals(setOf("x"), GroupSelectAll.next(setOf("x"), all, 1))
        // 已选满上限、可见行里有已选：按钮是「取消全选」，点了摘掉可见行（2026-10-07 口径）
        assertEquals(emptySet<String>(), GroupSelectAll.next(setOf("c"), all, 1))
    }

    @Test fun `limit zero or negative means unlimited`() {
        assertEquals(4, GroupSelectAll.next(emptySet(), all, -1).size)
        assertEquals(0, GroupSelectAll.limitOf(0))
        assertEquals(499, GroupSelectAll.limitOf(500))
    }

    @Test fun `all visible selected toggles to deselect only visible`() {
        val sel = setOf("a", "b", "x")
        assertTrue(GroupSelectAll.allSelected(sel, listOf("a", "b")))
        assertEquals(setOf("x"), GroupSelectAll.next(sel, listOf("a", "b"), 0))
    }

    @Test fun `empty visible is hidden and noop`() {
        assertFalse(GroupSelectAll.isVisible(emptyList()))
        assertFalse(GroupSelectAll.allSelected(setOf("a"), emptyList()))
        assertEquals(setOf("a"), GroupSelectAll.next(setOf("a"), emptyList(), 3))
    }

    @Test fun `filled to cap toggles to deselect`() {
        val vis = listOf("a", "b", "c", "d")
        val filled = GroupSelectAll.next(emptySet(), vis, 2)
        assertFalse(GroupSelectAll.allSelected(filled, vis))
        assertTrue(GroupSelectAll.showsDeselect(filled, vis, 2))
        assertEquals(emptySet<String>(), GroupSelectAll.next(filled, vis, 2))
        assertFalse(GroupSelectAll.showsDeselect(setOf("a"), vis, 2))
        // 满了但可见行一个没选：取消也无对象，仍是「全选」（点了不变）
        assertFalse(GroupSelectAll.showsDeselect(setOf("x", "y"), vis, 2))
        assertFalse(GroupSelectAll.showsDeselect(setOf("a", "b"), vis, 0))
    }
}
