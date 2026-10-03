package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.DbTx
import com.libeyond.imandroid.data.db.FriendLocalDao
import com.libeyond.imandroid.data.db.FriendLocalEntity
import com.libeyond.imandroid.data.db.GroupLocalDao
import com.libeyond.imandroid.data.db.GroupLocalEntity
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 好友/群离线快照：只存 accepted、保持顺序、空列表也覆盖、没变就不重写、写失败不记指纹。 */
class RosterCacheTest {
    private class FakeFriends : FriendLocalDao {
        val rows = mutableListOf<FriendLocalEntity>()
        var inserts = 0
        var failInsert = false
        override suspend fun list(owner: String) = rows.filter { it.ownerUid == owner }.sortedBy { it.sortOrder }
        override suspend fun clear(owner: String) { rows.removeAll { it.ownerUid == owner } }
        override suspend fun insertAll(rows: List<FriendLocalEntity>) {
            if (failInsert) error("disk")
            inserts++; this.rows += rows
        }
    }

    private class FakeGroups : GroupLocalDao {
        val rows = mutableListOf<GroupLocalEntity>()
        override suspend fun list(owner: String) = rows.filter { it.ownerUid == owner }.sortedBy { it.sortOrder }
        override suspend fun clear(owner: String) { rows.removeAll { it.ownerUid == owner } }
        override suspend fun insertAll(rows: List<GroupLocalEntity>) { this.rows += rows }
    }

    private val passTx = object : DbTx { override suspend fun <R> run(block: suspend () -> R): R = block() }

    private fun f(id: String, status: String = FriendEntry.ACCEPTED, nick: String = "n$id", blocked: Boolean = false) =
        FriendEntry(userId = id, nickname = nick, status = status, blocked = blocked)

    @Test fun `只存 accepted，顺序保持，读回仍是 accepted`() = runTest {
        val dao = FakeFriends(); val c = RosterCache(dao, FakeGroups(), passTx)
        c.saveFriends("me", listOf(f("b"), f("p", FriendEntry.PENDING), f("a"), f("r", FriendEntry.REQUESTED)))
        assertEquals(listOf("b", "a"), c.cachedFriends("me").map { it.userId })
        assertTrue(c.cachedFriends("me").all { it.status == FriendEntry.ACCEPTED })
    }

    @Test fun `blocked 与 status 正交，单独存`() = runTest {
        val c = RosterCache(FakeFriends(), FakeGroups(), passTx)
        c.saveFriends("me", listOf(f("a", blocked = true)))
        assertTrue(c.cachedFriends("me").single().blocked)
    }

    @Test fun `空列表也覆盖（确实没有好友）`() = runTest {
        val c = RosterCache(FakeFriends(), FakeGroups(), passTx)
        c.saveFriends("me", listOf(f("a")))
        c.saveFriends("me", emptyList())
        assertTrue(c.cachedFriends("me").isEmpty())
    }

    @Test fun `内容没变不重写，变了才写`() = runTest {
        val dao = FakeFriends(); val c = RosterCache(dao, FakeGroups(), passTx)
        c.saveFriends("me", listOf(f("a"))); c.saveFriends("me", listOf(f("a")))
        assertEquals(1, dao.inserts)
        c.saveFriends("me", listOf(f("a", nick = "改名了")))
        assertEquals(2, dao.inserts)
    }

    @Test fun `写失败不记指纹，下次同样内容还会重试`() = runTest {
        val dao = FakeFriends(); val c = RosterCache(dao, FakeGroups(), passTx)
        dao.failInsert = true
        runCatching { c.saveFriends("me", listOf(f("a"))) }
        dao.failInsert = false
        c.saveFriends("me", listOf(f("a")))
        assertEquals(1, dao.inserts)
    }

    @Test fun `账号隔离`() = runTest {
        val c = RosterCache(FakeFriends(), FakeGroups(), passTx)
        c.saveFriends("me", listOf(f("a"))); c.saveFriends("other", listOf(f("z")))
        assertEquals(listOf("a"), c.cachedFriends("me").map { it.userId })
    }

    @Test fun `群列表整表覆盖并保序，群主公开资料带回`() = runTest {
        val c = RosterCache(FakeFriends(), FakeGroups(), passTx)
        c.saveGroups("me", listOf(GroupInfo(convId = "g2", name = "二", ownerNickname = "群主"), GroupInfo(convId = "g1", name = "一")))
        val back = c.cachedGroups("me")
        assertEquals(listOf("g2", "g1"), back.map { it.convId })
        assertEquals("群主", back.first().ownerNickname)
        c.saveGroups("me", emptyList())
        assertFalse(c.cachedGroups("me").isNotEmpty())
    }
}
