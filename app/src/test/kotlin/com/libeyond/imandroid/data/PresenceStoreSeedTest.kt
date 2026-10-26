package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** HTTP 快照是初始值来源：对方改成「上次上线不可见」后快照里在线态为空，必须清掉旧副标题数据。 */
class PresenceStoreSeedTest {

    @Test
    fun seed_with_status_stores_it() {
        val s = PresenceStore()
        s.seed("u1", "online", 100, 50)
        assertEquals(PresenceSnapshot("online", 100, 50), s.snapshotOf("u1"))
    }

    @Test
    fun empty_status_snapshot_clears_the_stale_entry() {
        val s = PresenceStore()
        s.seed("u1", "online", 100, 50)
        s.seed("u1", "", 0, 0)
        assertEquals(PresenceSnapshot(), s.snapshotOf("u1"))
        assertEquals(false, s.presence.value.containsKey("u1"))
    }

    @Test
    fun empty_uid_is_ignored() {
        val s = PresenceStore()
        s.seed("", "", 0, 0)
        assertEquals(emptyMap<String, PresenceSnapshot>(), s.presence.value)
    }
}
