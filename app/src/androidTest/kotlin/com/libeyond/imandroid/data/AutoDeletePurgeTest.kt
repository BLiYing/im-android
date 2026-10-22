package com.libeyond.imandroid.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.IMDatabase
import com.libeyond.imandroid.data.db.RoomTx
import com.libeyond.imandroid.sdk.api.AutoDeleteFloor
import com.libeyond.imandroid.sdk.api.ConversationSummary
import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
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

    private suspend fun seedConv(
        sec: Int, from: Long = 0, unread: Int = 0, readSeq: Long = 0, lastSeq: Long = 0, lastTs: Long = 0, lastContent: String = "",
    ) {
        db.conversations().upsert(
            ConversationEntity(
                ownerUid = me, convId = conv, autoDeleteSec = sec, autoDeleteFrom = from, unread = unread, readSeq = readSeq,
                lastConvSeq = lastSeq, lastTimestamp = lastTs, lastContent = lastContent, lastFrom = "peer", mentionUnread = unread > 0,
            ),
        )
    }

    private suspend fun seqs() = db.messages().latestWindow(me, conv, 100).map { it.convSeq }.sorted()

    @Test
    fun purgeOnlyTouchesTheWindowAndNeverHistoryBeforeFrom() = runBlocking {
        seedConv(sec = 0, unread = 3, readSeq = 0, lastSeq = 4, lastTs = now - 10, lastContent = "m4")
        repo.onSyncPage(
            me, conv,
            listOf(md(1, now - 5 * day), md(2, now - 3 * day), md(3, now - 2 * day), md(4, now - 10)), covered = 4, sinceHint = 0,
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs()) // 关闭时全留
        // v2：开启 1 天，from = 2.5 天前。1、2 在 from 之前永不过期；3 在窗口 [from, now-1d] 内；4 太新
        db.conversations().setAutoDelete(me, conv, 86_400, now - 5 * day / 2)
        val purged = repo.purgeExpired(me, conv, now)
        assertEquals(listOf(3L), purged)
        assertEquals(listOf(1L, 2L, 4L), seqs())
        val c = db.conversations().byId(me, conv)!!
        assertEquals("m4", c.lastContent) // 最后一条没过期：预览不动
        assertEquals(2, c.unread) // 3 条未读里 1 条（seq3）过期
    }

    @Test
    fun previewCrossesTheWindowToTheLastVisibleMessageBeforeFrom() = runBlocking {
        // 先关着入库（开启状态下入库即过滤，且入库过滤用真实时钟、本测试的 now 是虚拟的），再开启
        seedConv(sec = 0, unread = 1, lastSeq = 2, lastTs = now - 2 * day, lastContent = "m2")
        repo.onSyncPage(me, conv, listOf(md(1, now - 4 * day), md(2, now - 2 * day)), covered = 2, sinceHint = 0)
        db.conversations().setAutoDelete(me, conv, 86_400, now - 5 * day / 2)
        assertEquals(listOf(2L), repo.purgeExpired(me, conv, now))
        assertEquals(listOf(1L), seqs())
        val c = db.conversations().byId(me, conv)!!
        assertEquals("m1", c.lastContent) // 越过窗口取 from 之前的那条
        assertEquals(now - 2 * day, c.lastTimestamp) // 排序位置保留，不清零
        assertEquals(0, c.unread)
    }

    @Test
    fun fromZeroWindowCoversWholeHistoryAndLeavesEmptyPreview() = runBlocking {
        seedConv(sec = 0, unread = 2, readSeq = 0, lastSeq = 2, lastTs = now - 2 * day, lastContent = "m2")
        repo.onSyncPage(me, conv, listOf(md(1, now - 3 * day), md(2, now - 2 * day)), covered = 2, sinceHint = 0)
        db.conversations().setAutoDelete(me, conv, 86_400, 0)
        assertEquals(listOf(1L, 2L), repo.purgeExpired(me, conv, now).sorted())
        assertEquals(emptyList<Long>(), seqs())
        val c = db.conversations().byId(me, conv)!! // 会话行保留，预览空、未读 0
        assertEquals("", c.lastContent)
        assertEquals(0, c.unread)
        assertTrue(!c.mentionUnread)
        assertEquals(now - 2 * day, c.lastTimestamp)
    }

    @Test
    fun exactBoundaryIsExpiredAndOneMsLaterIsNot() = runBlocking {
        seedConv(sec = 0, lastSeq = 2, lastTs = now - day + 1, lastContent = "m2")
        repo.onSyncPage(me, conv, listOf(md(1, now - day), md(2, now - day + 1)), covered = 2, sinceHint = 0)
        db.conversations().setAutoDelete(me, conv, 86_400, 0)
        repo.purgeExpired(me, conv, now)
        assertEquals(listOf(2L), seqs()) // ts + 1d == now → 过期（含等号）；差 1ms 不过期
    }

    @Test
    fun expiredMessagesAreNotStoredOnArrivalButBeforeFromOnesAre() = runBlocking {
        val t = System.currentTimeMillis()
        val old = t - 5 * day
        seedConv(sec = 86_400, from = old + 10) // old、old+1 在 from 之前：不过期；old+10.. 窗口内：过期
        val fresh = t
        assertEquals(null, repo.onSyncPage(me, conv, listOf(md(1, old), md(2, old + 1), md(3, old + 20), md(4, fresh)), covered = 4, sinceHint = 0))
        assertEquals(listOf(1L, 2L, 4L), seqs()) // 窗口内的 3 不进库；from 之前的老消息正常入库
        assertEquals(4L, db.conversations().byId(me, conv)!!.syncedConvSeq) // 游标照推，不留缺口
        assertEquals(listOf(1L to 4L), repo.ranges.ranges(me, conv).map { it.lo to it.hi })
        // 实时一条入库即过期（时钟漂移 / 迟到帧）：不落库、不 bump 会话，但序号登记
        assertEquals(false, repo.onIncoming(me, md(5, old + 30), bumpUnread = true))
        assertEquals(listOf(1L, 2L, 4L), seqs())
        assertEquals(5L, db.conversations().byId(me, conv)!!.syncedConvSeq)
    }

    @Test
    fun emptyPreviewIsRecomputedFromOlderVisibleMessageAndNotRewrittenTwice() = runBlocking {
        // 预览被清空（keepAutoDeleteTail 或服务端过滤）但 from 之前还有可见消息：补回；再扫一遍不再变
        seedConv(sec = 86_400, from = now - 5 * day / 2, lastSeq = 2, lastTs = now - 2 * day, lastContent = "")
        repo.onSyncPage(me, conv, listOf(md(1, now - 4 * day), md(2, now - 2 * day)), covered = 2, sinceHint = 0)
        repo.purgeExpired(me, conv, now)
        assertEquals("m1", db.conversations().byId(me, conv)!!.lastContent)
        assertTrue(repo.purgeExpired(me, conv, now).isEmpty())
        // 预览空且没有任何本地消息：不写（行不变）；撤回标记 / 空内容的残留会被重算清掉
        db.conversations().upsert(
            ConversationEntity(
                ownerUid = me, convId = conv, autoDeleteSec = 86_400, lastTimestamp = now - 2 * day, lastConvSeq = 1,
                lastRecalled = true, unread = 2, mentionUnread = true,
            ),
        )
        db.messages().clearConv(me, conv)
        repo.purgeExpired(me, conv, now)
        val c = db.conversations().byId(me, conv)!!
        assertTrue(!c.lastRecalled)
        assertEquals(2, c.unread) // 没有本地行可扣：未读留给服务端下次列表校准
    }

    @Test
    fun closedTierNeverPurges() = runBlocking {
        seedConv(sec = 0)
        repo.onSyncPage(me, conv, listOf(md(1, 1), md(2, 2)), covered = 2, sinceHint = 0)
        assertTrue(repo.purgeExpired(me, conv, now).isEmpty())
        assertEquals(listOf(1L, 2L), seqs())
    }
    private fun upd(sec: Int, from: Long) =
        ConvUpdateData(convId = conv, action = "settings", autoDeleteSec = sec, autoDeleteFrom = from)

    @Test
    fun settingsUpdateSweepsOnlyWhenTierOrFromChangesAndStaysOn() = runBlocking {
        seedConv(sec = 0)
        assertTrue(repo.applySettingsUpdate(me, upd(86_400, 1_000))) // 开启
        assertEquals(86_400 to 1_000L, db.conversations().byId(me, conv)!!.let { it.autoDeleteSec to it.autoDeleteFrom })
        assertTrue(!repo.applySettingsUpdate(me, upd(86_400, 1_000))) // 没变
        assertTrue(repo.applySettingsUpdate(me, upd(86_400, 2_000))) // 只 from 变
        assertTrue(repo.applySettingsUpdate(me, upd(604_800, 2_000))) // 只 sec 变
        assertTrue(!repo.applySettingsUpdate(me, upd(0, 0))) // 关闭不扫
        assertEquals(0, db.conversations().byId(me, conv)!!.autoDeleteSec)
    }

    @Test
    fun dirtyZeroFromIsTreatedAsOffAtEveryRepositoryEntryPoint() = runBlocking {
        // conv_update：sec>0 且 from=0 → 不开启、不扫、镜像也是关（否则会从 1970 起追溯清空）
        seedConv(sec = 0, lastSeq = 1, lastTs = 1, lastContent = "m1")
        repo.onSyncPage(me, conv, listOf(md(1, 1)), covered = 1, sinceHint = 0)
        assertTrue(!repo.applySettingsUpdate(me, upd(86_400, 0)))
        assertEquals(0, db.conversations().byId(me, conv)!!.autoDeleteSec)
        assertTrue(!AutoDeleteFloor.expired(conv, 1))
        assertTrue(repo.purgeExpired(me, conv, now).isEmpty())
        assertEquals(listOf(1L), seqs())
        // 会话列表快照：同样按关闭落库
        repo.applyConversationList(me, listOf(ConversationSummary(convId = conv, isGroup = true, autoDeleteSec = 86_400, autoDeleteFrom = 0)))
        assertEquals(0, db.conversations().byId(me, conv)!!.autoDeleteSec)
        // 合法值（from>0）照常落库
        repo.applyConversationList(me, listOf(ConversationSummary(convId = conv, isGroup = true, autoDeleteSec = 86_400, autoDeleteFrom = 5)))
        assertEquals(86_400, db.conversations().byId(me, conv)!!.autoDeleteSec)
        AutoDeleteFloor.clear()
    }
}
