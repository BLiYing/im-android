package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerDismissTest {
    @Test fun `拖过阈值松手就关`() {
        assertTrue(ViewerDismiss.shouldClose(ViewerDismiss.DISTANCE_DP, 0f))
        assertFalse(ViewerDismiss.shouldClose(ViewerDismiss.DISTANCE_DP - 1f, 0f))
    }

    @Test fun `短距离快速下甩也关`() {
        assertTrue(ViewerDismiss.shouldClose(20f, ViewerDismiss.FLING_DP_PER_S))
        assertFalse(ViewerDismiss.shouldClose(20f, ViewerDismiss.FLING_DP_PER_S - 1f))
    }

    @Test fun `没往下拖不因速度误关`() {
        assertFalse(ViewerDismiss.shouldClose(0f, 5000f))
    }

    @Test fun `视觉随拖动递减且封顶`() {
        assertEquals(1f, ViewerDismiss.scaleFor(0f), 0.0001f)
        assertEquals(0.8f, ViewerDismiss.scaleFor(ViewerDismiss.FULL_TRAVEL_DP * 2), 0.0001f)
        assertEquals(1f, ViewerDismiss.backdropAlphaFor(0f), 0.0001f)
        assertEquals(0.3f, ViewerDismiss.backdropAlphaFor(ViewerDismiss.FULL_TRAVEL_DP), 0.0001f)
    }
}
