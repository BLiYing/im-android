package com.libeyond.imandroid.ui.screens

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 气泡尾角（对齐 iOS `applyBubbleDirectionStyle`）：自己右下直角、对方左下直角、媒体四角全圆。 */
class BubbleShapeTest {
    private val size = Size(200f, 100f)
    private val density = Density(1f)
    private fun corners(mine: Boolean, tail: Boolean): List<Float> {
        val s = bubbleShape(12.dp, mine, tail)
        return listOf(s.topStart, s.topEnd, s.bottomEnd, s.bottomStart).map { it.toPx(size, density) }
    }

    @Test fun `自己的气泡右下角是直角`() = assertEquals(listOf(12f, 12f, 0f, 12f), corners(mine = true, tail = true))

    @Test fun `对方的气泡左下角是直角`() = assertEquals(listOf(12f, 12f, 12f, 0f), corners(mine = false, tail = true))

    @Test fun `图片视频无尾四角全圆`() {
        assertEquals(listOf(12f, 12f, 12f, 12f), corners(mine = true, tail = false))
        assertEquals(listOf(12f, 12f, 12f, 12f), corners(mine = false, tail = false))
    }
}
