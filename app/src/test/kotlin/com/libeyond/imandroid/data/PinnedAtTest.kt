package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedAtTest {

    @Test
    fun offClearsToZero() = assertEquals(0L, PinnedAt.next(newPinned = false, currentPinnedAt = 5000, nowMs = 9000))

    @Test
    fun offToOnTakesNow() = assertEquals(9000L, PinnedAt.next(newPinned = true, currentPinnedAt = 0, nowMs = 9000))

    @Test
    fun alreadyPinnedKeepsOriginalOrder() =
        assertEquals(5000L, PinnedAt.next(newPinned = true, currentPinnedAt = 5000, nowMs = 9000))
}
