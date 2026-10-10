package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRelationTest {
    private fun entry(status: String, username: String = "max1001", blocked: Boolean = false) =
        FriendEntry(userId = "u", username = username, status = status, blocked = blocked)

    @Test fun `好友：好友态与句柄来自同一行`() {
        val r = PeerRelation.of(entry(FriendEntry.ACCEPTED))
        assertTrue(r.isFriend)
        assertEquals("@max1001", r.handle)
    }

    @Test fun `不是好友或未知：句柄为空且不算好友`() {
        assertFalse(PeerRelation.of(null).isFriend)
        assertEquals("", PeerRelation.of(null).handle)
        assertFalse(PeerRelation.of(entry(FriendEntry.REQUESTED)).isFriend)
    }

    @Test fun `拉黑的好友仍算好友`() {
        val r = PeerRelation.of(entry(FriendEntry.ACCEPTED, blocked = true))
        assertTrue(r.isFriend)
        assertTrue(r.blocked)
    }

    @Test fun `无用户名时句柄为空串`() {
        assertEquals("", PeerRelation.of(entry(FriendEntry.ACCEPTED, username = "")).handle)
    }
}
