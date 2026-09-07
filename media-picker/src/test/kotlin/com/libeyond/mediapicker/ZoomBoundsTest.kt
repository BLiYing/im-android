package com.libeyond.mediapicker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缩放边界。不钳制的话放大后一甩就能把图拖出屏幕，剩一片黑，用户以为图没了。
 */
class ZoomBoundsTest {

    @Test
    fun `一倍时不允许任何平移`() {
        assertEquals(0f, ZoomBounds.clamp(500f, 1000f, 1f), 0.001f)
        // 小于 1 倍（回弹过程中）同样归零，否则会停在一个偏移的视图上，看着像「图跑偏了」
        assertEquals(0f, ZoomBounds.clamp(500f, 1000f, 0.8f), 0.001f)
    }

    @Test
    fun `最大平移是溢出量的一半`() {
        // 容器 1000、放大 2 倍 → 内容 2000、单边溢出 500
        assertEquals(500f, ZoomBounds.maxTranslation(1000f, 2f), 0.001f)
        assertEquals(0f, ZoomBounds.maxTranslation(1000f, 1f), 0.001f)
    }

    @Test
    fun `超出边界被钳回`() {
        assertEquals(500f, ZoomBounds.clamp(9999f, 1000f, 2f), 0.001f)
        assertEquals(-500f, ZoomBounds.clamp(-9999f, 1000f, 2f), 0.001f)
        assertEquals(120f, ZoomBounds.clamp(120f, 1000f, 2f), 0.001f)
    }
}
