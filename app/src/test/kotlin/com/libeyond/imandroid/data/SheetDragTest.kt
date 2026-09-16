package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.SheetDrag.Settle
import org.junit.Assert.assertEquals
import org.junit.Test

/** 卡片式弹层松手后收回还是关掉。 */
class SheetDragTest {

    private val height = 1000f
    private val fling = 2000f

    @Test
    fun `慢慢拖，过四分之一才关`() {
        assertEquals(Settle.Restore, SheetDrag.settle(200f, height, 0f, fling))
        assertEquals(Settle.Dismiss, SheetDrag.settle(260f, height, 0f, fling))
    }

    @Test
    fun `用力往下甩，拖得再少也关`() {
        assertEquals(Settle.Dismiss, SheetDrag.settle(30f, height, 2500f, fling))
    }

    @Test
    fun `拖下去一大截又往上推是不想关`() {
        assertEquals(Settle.Restore, SheetDrag.settle(600f, height, -2500f, fling))
    }

    @Test
    fun `根本没拖下来就不关`() {
        assertEquals(Settle.Restore, SheetDrag.settle(0f, height, 5000f, fling))
    }
}
