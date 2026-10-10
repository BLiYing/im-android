package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAddedTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun f(id: String, at: Long, status: String = FriendEntry.ACCEPTED) =
        FriendEntry(userId = id, status = status, updatedAt = at)

    @Test fun `30 day boundary is inclusive and one ms older is out`() {
        val r = recentAdded(listOf(f("in", now - 30 * day), f("out", now - 30 * day - 1)), now)
        assertEquals(listOf("in"), r.map { it.userId })
    }

    @Test fun `sorted by updatedAt desc`() {
        val r = recentAdded(listOf(f("a", now - 3 * day), f("b", now - day), f("c", now - 2 * day)), now)
        assertEquals(listOf("b", "c", "a"), r.map { it.userId })
    }

    @Test fun `capped at max keeping newest`() {
        val all = (0 until RECENT_ADDED_MAX + 10).map { f("u$it", now - it * 1000L) }
        val r = recentAdded(all, now)
        assertEquals(RECENT_ADDED_MAX, r.size)
        assertEquals("u0", r.first().userId)
        assertEquals("u${RECENT_ADDED_MAX - 1}", r.last().userId)
    }

    @Test fun `only accepted status`() {
        val r = recentAdded(
            listOf(
                f("p", now, FriendEntry.PENDING),
                f("r", now, FriendEntry.REQUESTED),
                f("b", now, "blocked"),
                f("ok", now),
            ),
            now,
        )
        assertEquals(listOf("ok"), r.map { it.userId })
    }

    @Test fun `constants match design`() {
        assertEquals(30, RECENT_ADDED_DAYS)
        assertEquals(50, RECENT_ADDED_MAX)
    }
}
