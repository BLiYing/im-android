package com.libeyond.imandroid.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.IMDatabase
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.data.db.RoomTx
import com.libeyond.imandroid.sdk.api.ClearedConv
import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「清除所有对话」本机落地（[clearAllToFloors]，OFFLINE_BACKLOG §6.7.1 例程的批量版）的真实 Room 语义：
 * 只清 ≤ cleared_at、新消息与待发保留、位点在行删后仍在、回声幂等、单事务回滚。内存库，不碰设备数据。
 */
@RunWith(AndroidJUnit4::class)
class ClearAllToFloorsTest {
    private lateinit var db: IMDatabase
    private lateinit var repo: MessageRepository
    private val me = "me"
    private val a = "u_1_u_2"
    private val b = "g_b"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IMDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MessageRepository(db.messages(), db.pending(), db.conversations(), ConvRanges(db.ranges()), RoomTx(db))
        listOf(a, b).forEach { db.conversations().upsert(ConversationEntity(ownerUid = me, convId = it)) }
    }

    @After
    fun close() = db.close()

    private fun md(conv: String, seq: Long) = MessageData(convId = conv, convSeq = seq, from = "peer", content = "m$seq", timestamp = seq)
    private suspend fun seqs(conv: String) = db.messages().latestWindow(me, conv, 50).map { it.convSeq }.sorted()

    @Test
    fun batchClearsOnlyUpToClearedAtPerConversationAndRemovesRows() = runBlocking {
        repo.onSyncPage(me, a, (1L..5L).map { md(a, it) }, covered = 5)
        repo.onSyncPage(me, b, (1L..3L).map { md(b, it) }, covered = 3)
        val n = repo.clearAllToFloors(me, listOf(ClearedConv(a, 3), ClearedConv(b, 3)))
        assertEquals(2, n)
        assertEquals(listOf(4L, 5L), seqs(a)) // 位点之上的新消息保留
        assertEquals(emptyList<Long>(), seqs(b))
        assertNull(db.conversations().byId(me, b)) // 位点以内：行移走
        assertNotNull(db.conversations().byId(me, a)) // 位点之上已有消息＝已复现：行保留
        assertEquals(listOf(SeqRange(4, 5)), repo.ranges.ranges(me, a))
    }

    @Test
    fun floorSurvivesRowDeletionAndPendingIsKept() = runBlocking {
        repo.onSyncPage(me, b, (1L..3L).map { md(b, it) }, covered = 3)
        db.pending().put(PendingMessageEntity(ownerUid = me, clientMsgId = "c1", convId = b, to = "x", contentType = "text", content = "draft"))
        repo.clearAllToFloors(me, listOf(ClearedConv(b, 3)))
        assertNull(db.conversations().byId(me, b))
        assertEquals(3L, repo.ranges.clearFloor(me, b)) // 独立位点表，行删后仍在
        assertNotNull(db.pending().byClientId(me, "c1")) // 待发送保留（服务端位点口径）
        repo.onSyncPage(me, b, (2L..4L).map { md(b, it) }, covered = 4) // 在途旧页写不进 ≤ 位点
        assertEquals(listOf(4L), seqs(b))
    }

    @Test
    fun deleteEchoAfterBatchIsIdempotent() = runBlocking {
        repo.onSyncPage(me, b, (1L..3L).map { md(b, it) }, covered = 3)
        repo.clearAllToFloors(me, listOf(ClearedConv(b, 3)))
        repo.onIncoming(me, md(b, 4), bumpUnread = true) // 清除后新消息：会话复现
        repo.applyConvUpdate(me, com.libeyond.imandroid.sdk.protocol.ConvUpdateData(convId = b, action = "delete", clearedAt = 3)) // 迟到回声
        assertEquals(listOf(4L), seqs(b))
        assertNotNull(db.conversations().byId(me, b)) // 复现的会话不被回声移走
        assertEquals(3L, repo.ranges.clearFloor(me, b))
    }

    @Test
    fun emptyItemsIsNoop() = runBlocking {
        repo.onSyncPage(me, a, (1L..2L).map { md(a, it) }, covered = 2)
        assertEquals(0, repo.clearAllToFloors(me, emptyList()))
        assertEquals(listOf(1L, 2L), seqs(a))
    }
}
