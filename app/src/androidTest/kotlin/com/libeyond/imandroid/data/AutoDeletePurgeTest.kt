package com.libeyond.imandroid.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.IMDatabase
import com.libeyond.imandroid.data.db.RoomTx
import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自动删除的**真实 SQL**（`expiredSeqs` / `countExpiredUnread` / `deleteExpired` / `updatePreview`）与入库过滤。
 * 内存库，不碰真机登录态。跑法同 [com.libeyond.imandroid.data.db.MigrationTest]（`am instrument`，别用 connectedAndroidTest）。
 */
@RunWith(AndroidJUnit4::class)
class AutoDeletePurgeTest {
    private lateinit var db: IMDatabase
    private lateinit var repo: MessageRepository
    private val me = "me"
    private val conv = "g_c"
    private val day = 86_400_000L
    private val now = 10 * day

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IMDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MessageRepository(db.messages(), db.pending(), db.conversations(), ConvRanges(db.ranges()), RoomTx(db))
    }

    @After
    fun close() = db.close()

    private fun md(seq: Long, ts: Long, from: String = "peer") =
        MessageData(convId = conv, convSeq = seq, from = from, content = "m$seq", timestamp = ts)

    private suspend fun seedConv(sec: Int, unread: Int = 0, readSeq: Long = 0, lastSeq: Long = 0, lastTs: Long = 0, lastContent: String = "") {
        db.conversations().upsert(
            ConversationEntity(
                ownerUid = me, convId = conv, autoDeleteSec = sec, unread = unread, readSeq = readSeq,
                lastConvSeq = lastSeq, lastTimestamp = lastTs, lastContent = lastContent, lastFrom = "peer", mentionUnread = unread > 0,
            ),
        )
    }

    private suspend fun seqs() = db.messages().latestWindow(me, conv, 100).map { it.convSeq }.sorted()

    @Test
    fun purgeAfterTightenRemovesHistoryAndFixesPreviewAndUnread() = runBlocking {
        seedConv(sec = 0, unread = 2, readSeq = 1, lastSeq = 3, lastTs = now - 2 * day, lastContent = "m3")
        repo.onSyncPage(me, conv, listOf(md(1, now - 3 * day), md(2, now - 2 * day - 5, from = "peer"), md(3, now - 2 * day)), covered = 3, sinceHint = 0)
        assertEquals(listOf(1L, 2L, 3L), seqs()) // 关闭时全留
        db.conversations().setAutoDeleteSec(me, conv, 86_400) // 开启 1 天
        val purged = repo.purgeExpired(me, conv, now)
        assertEquals(listOf(1L, 2L, 3L), purged.sorted())
        assertEquals(emptyList<Long>(), seqs())
        val c = db.conversations().byId(me, conv)!! // 会话行保留，预览空、未读 0
        assertEquals("", c.lastContent)
        assertEquals(0, c.unread)
        assertTrue(!c.mentionUnread)
        assertEquals(now - 2 * day, c.lastTimestamp) // 排序位置保留
    }

    @Test
    fun partialPurgeKeepsNewestAndSubtractsExpiredUnread() = runBlocking {
        seedConv(sec = 0, unread = 3, readSeq = 0, lastSeq = 3, lastTs = now - 10, lastContent = "m3")
        repo.onSyncPage(me, conv, listOf(md(1, now - 2 * day), md(2, now - 3 * day), md(3, now - 10)), covered = 3, sinceHint = 0)
        db.conversations().setAutoDeleteSec(me, conv, 86_400)
        repo.purgeExpired(me, conv, now)
        assertEquals(listOf(3L), seqs())
        val c = db.conversations().byId(me, conv)!!
        assertEquals("m3", c.lastContent) // 最后一条没过期：预览不动
        assertEquals(1, c.unread) // 3 条未读里 2 条过期
    }

    @Test
    fun exactBoundaryIsExpiredAndOneMsLaterIsNot() = runBlocking {
        seedConv(sec = 0, lastSeq = 2, lastTs = now - day + 1, lastContent = "m2")
        repo.onSyncPage(me, conv, listOf(md(1, now - day), md(2, now - day + 1)), covered = 2, sinceHint = 0)
        db.conversations().setAutoDeleteSec(me, conv, 86_400)
        repo.purgeExpired(me, conv, now)
        assertEquals(listOf(2L), seqs()) // ts + 1d == now → 过期（含等号）；差 1ms 不过期
    }

    @Test
    fun expiredMessagesAreNotStoredOnArrivalButRangeAndCursorStillAdvance() = runBlocking {
        seedConv(sec = 86_400)
        val old = System.currentTimeMillis() - 5 * day
        val fresh = System.currentTimeMillis()
        assertEquals(null, repo.onSyncPage(me, conv, listOf(md(1, old), md(2, old + 1), md(3, fresh)), covered = 3, sinceHint = 0))
        assertEquals(listOf(3L), seqs()) // 过期的不进库
        assertEquals(3L, db.conversations().byId(me, conv)!!.syncedConvSeq) // 游标照推，不留缺口
        assertEquals(listOf(1L to 3L), repo.ranges.ranges(me, conv).map { it.lo to it.hi })
        // 实时一条入库即过期（时钟漂移 / 迟到帧）：不落库、不 bump 会话，但序号登记
        assertEquals(false, repo.onIncoming(me, md(4, old), bumpUnread = true))
        assertEquals(listOf(3L), seqs())
        assertEquals(4L, db.conversations().byId(me, conv)!!.syncedConvSeq)
    }

    @Test
    fun emptyPreviewSkipsRewriteButStaleRecalledOrUnreadStillCleared() = runBlocking {
        // 预览已空、无残留：不写（行不变）
        db.conversations().upsert(
            ConversationEntity(ownerUid = me, convId = conv, autoDeleteSec = 86_400, lastTimestamp = now - 2 * day, lastConvSeq = 1),
        )
        assertTrue(repo.purgeExpired(me, conv, now).isEmpty())
        assertEquals(0, db.conversations().byId(me, conv)!!.unread)
        // 内容为空但仍带撤回标记 / 未读 / @我：必须被重算清掉
        db.conversations().upsert(
            ConversationEntity(
                ownerUid = me, convId = conv, autoDeleteSec = 86_400, lastTimestamp = now - 2 * day, lastConvSeq = 1,
                lastRecalled = true, unread = 2, mentionUnread = true,
            ),
        )
        repo.purgeExpired(me, conv, now)
        val c = db.conversations().byId(me, conv)!!
        assertTrue(!c.lastRecalled)
        assertEquals(0, c.unread)
        assertTrue(!c.mentionUnread)
    }

    @Test
    fun closedTierNeverPurges() = runBlocking {
        seedConv(sec = 0)
        repo.onSyncPage(me, conv, listOf(md(1, 1), md(2, 2)), covered = 2, sinceHint = 0)
        assertTrue(repo.purgeExpired(me, conv, now).isEmpty())
        assertEquals(listOf(1L, 2L), seqs())
    }
}
