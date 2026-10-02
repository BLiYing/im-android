package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BacklogGapTest {

    @Test
    fun `普通会话 max_gap 是 400，超级群是 0`() {
        assertEquals(400L, BacklogGap.maxGap(isSuper = false))
        assertEquals(0L, BacklogGap.maxGap(isSuper = true))
    }

    @Test
    fun `cursorOf 把游标与 max_gap 一起带上`() {
        val normal = BacklogGap.cursorOf("g_a", 57L, isSuper = false)
        assertEquals(57L, normal.sinceConvSeq)
        assertEquals(400L, normal.maxGap)
        val sup = BacklogGap.cursorOf("g_super", 1200L, isSuper = true)
        assertEquals(1200L, sup.sinceConvSeq)
        assertEquals(0L, sup.maxGap)
    }
}
