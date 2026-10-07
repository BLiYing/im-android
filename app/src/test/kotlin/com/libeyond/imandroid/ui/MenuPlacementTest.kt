package com.libeyond.imandroid.ui

import com.libeyond.imandroid.ui.components.placeMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按菜单摆放：对齐 iOS 系统 UIContextMenu（iOS 26 模拟器实测）。可见区 [40, 800]，菜单 200 高。 */
class MenuPlacementTest {
    private fun place(top: Float, bottom: Float, canMove: Boolean = true, menuH: Float = 200f) =
        placeMenu(top, bottom, safeTop = 40f, safeBottom = 800f, menuH = menuH, minMenuH = 106f, gap = 8f, canMove = canMove)

    @Test fun roomBelow_menuBelow_previewStays() {
        val p = place(100f, 160f)
        assertFalse(p.above)
        assertEquals(0f, p.shift, 0.01f)
        assertEquals(168f, p.menuTop, 0.01f)
    }

    @Test fun nearBottom_menuFlipsAbove_previewStays() {
        val p = place(680f, 740f)
        assertTrue(p.above)
        assertEquals(0f, p.shift, 0.01f)
        assertEquals(1f, p.scale, 0.01f)
        assertEquals(680f - 8f - 200f, p.menuTop, 0.01f)
    }

    @Test fun partlyBelowScreen_slidesIntoView() {
        val p = place(700f, 900f)
        assertEquals(-100f, p.shift, 0.01f)
        assertTrue(p.above)
    }

    @Test fun partlyAboveScreen_slidesDown() {
        val p = place(-60f, 140f)
        assertEquals(100f, p.shift, 0.01f)
        assertFalse(p.above)
        assertEquals(248f, p.menuTop, 0.01f)
    }

    @Test fun neitherSideFits_previewMakesRoom() {
        // 预览 420 高：上方 152、下方 172 都不够 200 → 放空间大的下方，预览上移 28
        val p = place(200f, 620f)
        assertFalse(p.above)
        assertEquals(-28f, p.shift, 0.01f)
        assertEquals(200f, p.menuHeight, 0.01f)
    }

    @Test fun tallerThanScreen_scalesDownToFitMenu() {
        val p = place(0f, 1200f)
        assertTrue(p.scale < 1f)
        val h = 1200f * p.scale
        val top = 600f - h / 2f + p.shift
        val bottom = top + h
        assertTrue(top >= 40f - 0.01f && bottom <= 800f + 0.01f)
        val menuBottom = p.menuTop + p.menuHeight
        assertTrue(if (p.above) menuBottom <= top else p.menuTop >= bottom)
    }

    @Test fun withoutPreview_neverMoves() {
        val p = place(760f, 790f, canMove = false)
        assertEquals(0f, p.shift, 0.01f)
        assertEquals(1f, p.scale, 0.01f)
        assertTrue(p.above)
    }
}
