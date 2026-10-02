package com.libeyond.imandroid.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libeyond.imandroid.data.ConvRanges
import com.libeyond.imandroid.data.SeqRange
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 区间清单**真实 SQL**（`overlapping` / `deleteOverlapping` 的谓词、REPLACE、owner 隔离）。内存库，不碰真机数据。 */
@RunWith(AndroidJUnit4::class)
class ConvRangesTest {
    private lateinit var db: IMDatabase
    private lateinit var ranges: ConvRanges

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IMDatabase::class.java)
            .allowMainThreadQueries().build()
        ranges = ConvRanges(db.ranges())
    }

    @After
    fun close() = db.close()

    private fun r(lo: Long, hi: Long) = SeqRange(lo, hi)

    @Test
    fun separateIslandsStaySeparate() = runBlocking {
        ranges.register("me", "c", 1, 10)
        ranges.register("me", "c", 20, 30)
        assertEquals(listOf(r(1, 10), r(20, 30)), ranges.ranges("me", "c"))
    }

    @Test
    fun adjacentRangesMerge() = runBlocking {
        ranges.register("me", "c", 1, 10)
        ranges.register("me", "c", 11, 20) // hi+1 == lo
        assertEquals(listOf(r(1, 20)), ranges.ranges("me", "c"))
    }

    @Test
    fun oneGapApartDoesNotMerge() = runBlocking {
        ranges.register("me", "c", 1, 10)
        ranges.register("me", "c", 12, 20)
        assertEquals(listOf(r(1, 10), r(12, 20)), ranges.ranges("me", "c"))
    }

    @Test
    fun bridgingRangeSwallowsBothNeighbours() = runBlocking {
        ranges.register("me", "c", 1, 10)
        ranges.register("me", "c", 31, 40)
        ranges.register("me", "c", 11, 30)
        assertEquals(listOf(r(1, 40)), ranges.ranges("me", "c"))
    }

    @Test
    fun registeringSameSpanTwiceIsIdempotent() = runBlocking {
        repeat(3) { ranges.register("me", "c", 5, 9) }
        assertEquals(listOf(r(5, 9)), ranges.ranges("me", "c"))
    }

    @Test
    fun invalidInputIsIgnored() = runBlocking {
        ranges.register("me", "c", 9, 3)
        ranges.register("me", "c", 0, 0)
        assertEquals(emptyList<SeqRange>(), ranges.ranges("me", "c"))
    }

    @Test
    fun coversNeedsOneSegment() = runBlocking {
        ranges.register("me", "c", 1, 10)
        ranges.register("me", "c", 20, 30)
        assertTrue(ranges.covers("me", "c", 3, 8))
        assertFalse(ranges.covers("me", "c", 8, 22))
    }

    @Test
    fun scopedByOwnerAndConv() = runBlocking {
        ranges.register("me", "c", 1, 10)
        ranges.register("other", "c", 1, 99)
        ranges.register("me", "d", 1, 5)
        assertEquals(listOf(r(1, 10)), ranges.ranges("me", "c"))
        ranges.clearConv("me", "c")
        assertEquals(emptyList<SeqRange>(), ranges.ranges("me", "c"))
        assertEquals(listOf(r(1, 99)), ranges.ranges("other", "c"))
        assertEquals(listOf(r(1, 5)), ranges.ranges("me", "d"))
        ranges.clearAccount("me")
        assertEquals(emptyList<SeqRange>(), ranges.ranges("me", "d"))
        assertEquals(listOf(r(1, 99)), ranges.ranges("other", "c"))
    }
}
