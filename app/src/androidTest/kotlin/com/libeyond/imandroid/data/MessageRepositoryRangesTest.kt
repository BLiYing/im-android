package com.libeyond.imandroid.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libeyond.imandroid.data.db.ConvRangeDao
import com.libeyond.imandroid.data.db.ConvRangeEntity
import com.libeyond.imandroid.data.db.ConversationDao
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.IMDatabase
import com.libeyond.imandroid.data.db.MessageDao
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.RoomTx
import com.libeyond.imandroid.sdk.api.ConversationSummary
import com.libeyond.imandroid.sdk.protocol.AckData
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ConvBumpItem
import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **I1（OFFLINE_BACKLOG_DESIGN §4.2）的真实事务语义**：区间只断言「已落库」，写消息、登记区间、推游标同一事务。
 * 用真 Room（内存库）+ 会按需抛错的 DAO 代理，验证回滚方向对不对——JVM 单测里没有事务，测不出。
 */
@RunWith(AndroidJUnit4::class)
class MessageRepositoryRangesTest {
    private lateinit var db: IMDatabase
    private var failBatch = false
    private var failSingleSeq: Long? = null
    private var failRangeInsert = false
    private var failCursorWrite = false
    private lateinit var repo: MessageRepository

    private val me = "me"
    private val conv = "g_c"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IMDatabase::class.java)
            .allowMainThreadQueries().build()
        val realMessages = db.messages()
        val messages = object : MessageDao by realMessages {
            override suspend fun upsert(messages: List<MessageEntity>) {
                if (failBatch) error("boom-batch")
                realMessages.upsert(messages)
            }

            override suspend fun upsert(message: MessageEntity) {
                if (message.convSeq == failSingleSeq) error("boom-single")
                realMessages.upsert(message)
            }
        }
        val realRanges = db.ranges()
        val rangeDao = object : ConvRangeDao by realRanges {
            override suspend fun insert(row: ConvRangeEntity) {
                if (failRangeInsert) error("boom-range")
                realRanges.insert(row)
            }
        }
        val realConvs = db.conversations()
        val convs = object : ConversationDao by realConvs {
            override suspend fun setSyncedConvSeq(owner: String, convId: String, seq: Long) {
                if (failCursorWrite) error("boom-cursor")
                realConvs.setSyncedConvSeq(owner, convId, seq)
            }
        }
        repo = MessageRepository(messages, db.pending(), convs, ConvRanges(rangeDao), RoomTx(db))
        db.conversations().upsert(ConversationEntity(ownerUid = me, convId = conv))
    }

    @After
    fun close() = db.close()

    private fun mdImg(seq: Long) = MessageData(convId = conv, convSeq = seq, from = "peer", content = "/u/$seq.jpg", contentType = ContentType.IMAGE, timestamp = seq)
    private fun md(seq: Long) = MessageData(convId = conv, convSeq = seq, from = "peer", content = "m$seq", timestamp = seq)
    private fun page(vararg seqs: Long) = seqs.map { md(it) }
    private suspend fun synced() = db.conversations().byId(me, conv)!!.syncedConvSeq
    private suspend fun ranges() = repo.ranges.ranges(me, conv)

    @Test
    fun syncPageWritesMessagesRangeAndCursorTogether() = runBlocking {
        assertNull(repo.onSyncPage(me, conv, page(1, 2, 3, 4, 5), covered = 5))
        assertEquals(5, db.messages().countIn(me, conv))
        assertEquals(listOf(SeqRange(1, 5)), ranges())
        assertEquals(5L, synced())
    }

    @Test
    fun syncPageRangeCoversHiddenSeqsUpToCovered() = runBlocking {
        // 服务端只下发 1、2、5，但断言 (0, 8] 全部看过（3、4 是事件行/不可见，6~8 是墓碑等）
        repo.onSyncPage(me, conv, page(1, 2, 5), covered = 8)
        assertEquals(listOf(SeqRange(1, 8)), ranges())
        assertEquals(8L, synced())
    }

    @Test
    fun tooLongPageRegistersNothingAndKeepsCursor() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        repo.onSyncPage(me, conv, emptyList(), covered = 3) // too_long：无消息、covered == since
        assertEquals(listOf(SeqRange(1, 3)), ranges())
        assertEquals(3L, synced())
    }

    @Test
    fun batchFailureRollsBackThenRetriesPerRowAndStillRegistersWholeRange() = runBlocking {
        failBatch = true // 整页写失败，但逐条能写
        assertNull(repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3))
        assertEquals(3, db.messages().countIn(me, conv))
        assertEquals(listOf(SeqRange(1, 3)), ranges())
        assertEquals(3L, synced())
    }

    @Test
    fun rowFailureStopsRangeAndCursorBeforeFirstFailedSeq() = runBlocking {
        failBatch = true
        failSingleSeq = 3
        assertEquals(3L, repo.onSyncPage(me, conv, page(1, 2, 3, 4, 5), covered = 5))
        assertEquals(2, db.messages().countIn(me, conv)) // 只有 1、2 落了
        assertEquals(listOf(SeqRange(1, 2)), ranges()) // 区间绝不越过没落库的那条
        assertEquals(2L, synced())
    }

    /** I1 的方向：登记区间失败 → 整个事务回滚，不会出现「游标/区间走在消息前面」。 */
    @Test
    fun rangeFailureNeverLeavesCursorOrRangeAheadOfRows() = runBlocking {
        failRangeInsert = true
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        assertEquals(emptyList<SeqRange>(), ranges())
        assertEquals(0L, synced()) // 游标没推：下次重拉（幂等）
    }

    /** 事务的另一个方向：区间已登记、游标写失败 → 区间必须一起回滚（否则区间与游标各走各的）。 */
    @Test
    fun cursorWriteFailureRollsTheRangeBack() = runBlocking {
        failCursorWrite = true
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        assertEquals(emptyList<SeqRange>(), ranges())
        assertEquals(0L, synced())
    }

    @Test
    fun windowRegistersItsSpanButNeverMovesTheCursor() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2), covered = 2)
        assertNull(repo.onWindowPage(me, conv, page(100, 101, 103))) // 102 是占号行：区间含它，因为在 [100,103] 内
        assertEquals(listOf(SeqRange(1, 2), SeqRange(100, 103)), ranges())
        assertEquals(2L, synced())
    }

    @Test
    fun realtimeMessageRegistersItselfAndMergesWithTail() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        repo.onIncoming(me, md(4), bumpUnread = false)
        assertEquals(listOf(SeqRange(1, 4)), ranges())
        repo.onIncoming(me, md(9), bumpUnread = false) // 跳号：登记成孤岛，不假装中间齐全
        assertEquals(listOf(SeqRange(1, 4), SeqRange(9, 9)), ranges())
    }

    @Test
    fun clearConversationClearsRangesAndSetsFloorAtLatestKnown() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        repo.clearConversation(me, conv)
        assertEquals(0, db.messages().countIn(me, conv))
        assertEquals(emptyList<SeqRange>(), ranges())
        assertEquals(3L, db.conversations().byId(me, conv)!!.clearedUpTo)
        assertEquals(3L, synced())
    }

    /** 本地有缺口时清空：位点取 head（服务端最新），游标一并推过去，不会再从旧游标把清掉的重拉。 */
    @Test
    fun clearWithGapRaisesFloorAndCursorToHead() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3, 4, 5), covered = 5)
        repo.noteHead(me, conv, 100)
        repo.clearConversation(me, conv)
        val c = db.conversations().byId(me, conv)!!
        assertEquals(100L, c.clearedUpTo)
        assertEquals(100L, c.syncedConvSeq)
    }

    /** 清掉的那一段不能被 window / sync 又带回来；位点之后的照收。 */
    @Test
    fun clearedSpanIsNeverWrittenBackByWindowOrSync() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3, 4, 5), covered = 5)
        repo.clearConversation(me, conv)
        repo.onWindowPage(me, conv, page(3, 4, 6, 7)) // 3、4 ≤ 位点 5：丢；6、7 收
        assertEquals(listOf(6L, 7L), db.messages().latestWindow(me, conv, 10).map { it.convSeq }.sorted())
        repo.onSyncPage(me, conv, page(1, 8), covered = 8) // 迟到的旧页里也夹着旧序号
        assertEquals(listOf(6L, 7L, 8L), db.messages().latestWindow(me, conv, 10).map { it.convSeq }.sorted())
    }

    /** 会话列表刷新是整行重写——位点是纯本机状态，不能被刷掉（否则清掉的历史下次进会话又拉回来）。 */
    @Test
    fun conversationListRefreshKeepsClearedFloor() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        repo.clearConversation(me, conv)
        repo.applyConversationList(me, listOf(ConversationSummary(convId = conv, isGroup = true, latestConvSeq = 9)))
        val c = db.conversations().byId(me, conv)!!
        assertEquals(3L, c.clearedUpTo)
        assertEquals(9L, c.headConvSeq)
    }

    @Test
    fun clearedFloorOnlyMovesForward() = runBlocking {
        db.conversations().raiseClearedUpTo(me, conv, 50)
        db.conversations().raiseClearedUpTo(me, conv, 20)
        assertEquals(50L, db.conversations().byId(me, conv)!!.clearedUpTo)
    }

    /** 复查抓出的遗漏：实时 msg_op 事件行占号，不登记则下一条成孤岛、清单永远差一格。 */
    @Test
    fun realtimeMsgOpRowRegistersItsSeqSoTheNextMessageStaysAdjacent() = runBlocking {
        repo.onSyncPage(me, conv, (1L..10L).map { md(it) }, covered = 10)
        repo.onIncoming(me, MessageData(convId = conv, convSeq = 11, from = "peer", contentType = ContentType.MSG_OP, content = "{}"), bumpUnread = false)
        repo.onIncoming(me, md(12), bumpUnread = false)
        assertEquals(listOf(SeqRange(1, 12)), ranges())
    }

    @Test
    fun ownAckRegistersItsSeq() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        repo.onAck(me, AckData(clientMsgId = "c1", serverMsgId = "s1", convId = conv, convSeq = 4, timestamp = 4))
        repo.onIncoming(me, md(5), bumpUnread = false)
        assertEquals(listOf(SeqRange(1, 5)), ranges())
    }

    @Test
    fun syncPageWithEventRowsStillRegistersTheWholeCoveredSpan() = runBlocking {
        val list = listOf(md(1), MessageData(convId = conv, convSeq = 2, from = "peer", contentType = ContentType.MSG_OP, content = "{}"), md(3))
        repo.onSyncPage(me, conv, list, covered = 3)
        assertEquals(2, db.messages().countIn(me, conv)) // 事件行不进 message 表
        assertEquals(listOf(SeqRange(1, 3)), ranges())
    }

    /** 会话行还没建：区间照登记（消息确实落了），游标没处可写——区间领先游标但不领先消息，无害；钉住这个行为。 */
    @Test
    fun missingConversationRowStillRegistersRangeButCannotMoveCursor() = runBlocking {
        repo.onSyncPage(me, "g_ghost", page(1, 2).map { it.copy(convId = "g_ghost") }, covered = 2)
        assertEquals(listOf(SeqRange(1, 2)), repo.ranges.ranges(me, "g_ghost"))
        assertNull(db.conversations().byId(me, "g_ghost"))
    }

    @Test
    fun clearAccountClearsRangesToo() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2), covered = 2)
        repo.clearAccount(me)
        assertEquals(emptyList<SeqRange>(), ranges())
        assertEquals(0, db.messages().countIn(me, conv))
    }

    // —— C4：实时消息推进游标 / conv_bump ——

    @Test
    fun realtimeMessageAdvancesCursorOnlyWhenContiguous() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        repo.onIncoming(me, md(4), bumpUnread = false)
        assertEquals(4L, synced()) // 接在游标后面：游标跟着走（不然下次重连把它重拉一遍）
        repo.onIncoming(me, md(9), bumpUnread = false)
        assertEquals(4L, synced()) // 跳号：游标绝不越过没收到的 5..8
        assertEquals(listOf(SeqRange(1, 4), SeqRange(9, 9)), ranges())
    }

    @Test
    fun ownAckAdvancesCursorWhenContiguous() = runBlocking {
        repo.onSyncPage(me, conv, page(1, 2, 3), covered = 3)
        repo.onAck(me, AckData(clientMsgId = "c1", serverMsgId = "s1", convId = conv, convSeq = 4, timestamp = 4))
        assertEquals(4L, synced())
    }

    @Test
    fun bumpSignalRaisesHeadAndRefreshesTheListRowWithoutTouchingUnread() = runBlocking {
        db.conversations().upsert(ConversationEntity(ownerUid = me, convId = conv, lastConvSeq = 10, lastContent = "旧", unread = 3))
        repo.applyBumpSignal(me, ConvBumpItem(convId = conv, latestSeq = 500, from = "u9", fromNickname = "小明", preview = "[图片]"))
        val c = db.conversations().byId(me, conv)!!
        assertEquals(500L, c.headConvSeq)
        assertEquals(500L, c.lastConvSeq)
        assertEquals("[图片]", c.lastContent)
        assertEquals("小明", c.lastFromNickname)
        assertEquals(3, c.unread) // 未读走整表刷新的服务端权威值，这里不瞎加
    }

    @Test
    fun staleBumpSignalDoesNotRegressThePreview() = runBlocking {
        db.conversations().upsert(ConversationEntity(ownerUid = me, convId = conv, lastConvSeq = 500, lastContent = "新"))
        repo.applyBumpSignal(me, ConvBumpItem(convId = conv, latestSeq = 300, preview = "旧信号"))
        val c = db.conversations().byId(me, conv)!!
        assertEquals("新", c.lastContent)
        assertEquals(500L, c.lastConvSeq)
    }

    @Test
    fun bumpForUnknownConversationOnlyAttemptsHeadAndCreatesNoPlaceholderRow() = runBlocking {
        repo.applyBumpSignal(me, ConvBumpItem(convId = "g_unknown", latestSeq = 7, preview = "x"))
        assertNull(db.conversations().byId(me, "g_unknown"))
    }

    // —— C4b：最新页现状 / 尾窗下界 / head 流 ——

    @Test
    fun tailStateSingleCompleteSegmentIsCoveredAndStartsAtOne() = runBlocking {
        repo.onSyncPage(me, conv, (1L..300L).map { md(it) }, covered = 300)
        repo.noteHead(me, conv, 300)
        val st = repo.tailState(me, conv, 100)
        assertEquals(300L, st.tip)
        assertEquals(true, st.covered)
        assertEquals(1L, st.segmentLo)
    }

    /** 缺口：旧段 [1,300]、最新只收到一个孤岛 [900,900]，head=1000——最新页没被覆盖，尾窗下界是孤岛而不是 1。 */
    @Test
    fun tailStateWithGapIsNotCoveredAndNeverStitchesTheOldIsland() = runBlocking {
        repo.onSyncPage(me, conv, (1L..300L).map { md(it) }, covered = 300)
        repo.noteHead(me, conv, 1000)
        repo.onIncoming(me, md(1000), bumpUnread = false) // 跳号实时消息登记成孤岛 [1000,1000]
        val st = repo.tailState(me, conv, 100)
        assertEquals(1000L, st.tip)
        assertEquals(false, st.covered) // 本地最大 seq 已经 == head，但 [901,1000] 没被同一段盖住
        assertEquals(1000L, st.segmentLo)
    }

    @Test
    fun tailStateAfterServerWindowIsCoveredByTheNewSegment() = runBlocking {
        repo.onSyncPage(me, conv, (1L..300L).map { md(it) }, covered = 300)
        repo.noteHead(me, conv, 1000)
        repo.onWindowPage(me, conv, (901L..1000L).map { md(it) }) // window_req(anchor=0, before=100) 的结果
        val st = repo.tailState(me, conv, 100)
        assertEquals(true, st.covered) // 下沿 tip-page+1=901：刚取回的一页必须判齐，不能每次 ↓ 白问
        assertEquals(901L, st.segmentLo)
    }

    @Test
    fun tailStateRespectsClearedFloor() = runBlocking {
        repo.onSyncPage(me, conv, (1L..30L).map { md(it) }, covered = 30)
        repo.noteHead(me, conv, 30)
        repo.clearConversation(me, conv)
        val st = repo.tailState(me, conv, 100)
        assertEquals(31L, st.visibleFrom)
        assertEquals(true, st.covered) // tip(30) < visibleFrom(31)：可见范围内没有东西，视为齐
        assertEquals(false, ChatTailPlan.shouldRequestTail(st.tip, st.covered, 0, st.visibleFrom))
    }

    @Test
    fun observeTailFromNeverIncludesRowsBelowTheBound() = runBlocking {
        repo.onSyncPage(me, conv, (1L..10L).map { md(it) }, covered = 10)
        repo.onWindowPage(me, conv, (900L..905L).map { md(it) })
        val rows = repo.observeTail(me, conv, fromSeq = 900, limit = 200).first()
        assertEquals((900L..905L).toList(), rows.map { it.convSeq })
        val all = repo.observeTail(me, conv, fromSeq = 1, limit = 200).first()
        assertEquals(16, all.size)
    }

    @Test
    fun observeHeadEmitsOnRaiseAndNotForMissingRow() = runBlocking {
        assertEquals(0L, repo.observeHead(me, conv).first())
        repo.noteHead(me, conv, 77)
        assertEquals(77L, repo.observeHead(me, conv).first())
    }

    // —— C3：进会话分流 / 本地开窗 / 段内取 ——

    @Test
    fun planEntryAsksServerWhenTheUnreadAnchorIsNotCovered() = runBlocking {
        repo.noteHead(me, conv, 100_000)
        val p = repo.planEntry(me, conv, readSeq = 99_000, unread = 50)
        assertEquals(EntryPlan.Server(anchor = 99_000, before = 25, after = 100), p)
    }

    @Test
    fun planEntryIsLocalWhenTheLatestPageIsCoveredAndPresent() = runBlocking {
        repo.onSyncPage(me, conv, (1L..300L).map { md(it) }, covered = 300)
        repo.noteHead(me, conv, 300)
        assertEquals(EntryPlan.Local, repo.planEntry(me, conv, readSeq = 300, unread = 0))
    }

    @Test
    fun planEntryOnAClearedConversationNeverAsksTheServer() = runBlocking {
        repo.onSyncPage(me, conv, (1L..30L).map { md(it) }, covered = 30)
        repo.noteHead(me, conv, 30)
        repo.clearConversation(me, conv)
        assertEquals(EntryPlan.Local, repo.planEntry(me, conv, readSeq = 5, unread = 25))
    }

    /** 十万未读：读位点远在 tip 之前，服务端给的那一窗不含 tip——必须是锚点窗，不能硬当尾窗（否则 bump 会把人拽走）。 */
    @Test
    fun farUnreadEntryIsAnAnchoredWindowAroundTheReadPositionNotATail() = runBlocking {
        repo.onSyncPage(me, conv, (1L..50L).map { md(it) }, covered = 50) // 旧岛：不该被拼进来
        repo.noteHead(me, conv, 100_000)
        repo.onWindowPage(me, conv, (975L..1100L).map { md(it) })          // 服务端给的「读位点 1000 附近」那一窗
        val w = repo.localEntryWindow(me, conv, readSeq = 1000, unread = 99_000)
        check(w is ChatWindow.Anchored) { "应是锚点窗，实际 $w" }
        val rows = repo.messages.observeRange(me, conv, w.loTs, w.loSeq, w.hiTs, w.hiSeq).first().map { it.convSeq }.sorted()
        assertEquals(976L, rows.first())  // 读位点后第一条是 1001，往前带 ENTRY_BEFORE=25 条已读上下文
        assertEquals(1100L, rows.last())
        assertEquals(true, rows.none { it <= 50 }) // 旧岛没被拼进来
    }

    @Test
    fun smallUnreadInACompleteConversationStaysATailSoNewMessagesKeepComing() = runBlocking {
        repo.onSyncPage(me, conv, (1L..300L).map { md(it) }, covered = 300)
        repo.noteHead(me, conv, 300)
        val w = repo.localEntryWindow(me, conv, readSeq = 295, unread = 5)
        assertEquals(ChatWindow.Tail(ChatWindows.TAIL_LIMIT, fromSeq = 1), w)
    }

    @Test
    fun noUnreadEntryIsATailBoundedToTheLatestSegment() = runBlocking {
        repo.onSyncPage(me, conv, (1L..50L).map { md(it) }, covered = 50)
        repo.noteHead(me, conv, 1000)
        repo.onWindowPage(me, conv, (901L..1000L).map { md(it) })
        val w = repo.localEntryWindow(me, conv, readSeq = 1000, unread = 0)
        assertEquals(ChatWindow.Tail(ChatWindows.TAIL_LIMIT, fromSeq = 901), w)
    }

    /** 上滚：本段到头后 extendWindowOlder 原样返回（调用方据此去问服务端），不跨缺口拼旧岛。 */
    @Test
    fun extendWindowOlderStopsAtTheSegmentEdgeInsteadOfStitchingTheOldIsland() = runBlocking {
        repo.onSyncPage(me, conv, (1L..10L).map { md(it) }, covered = 10)
        repo.onWindowPage(me, conv, (900L..905L).map { md(it) })
        val w = ChatWindow.Anchored(loTs = 900, loSeq = 900, hiTs = 905, hiSeq = 905) // 显式构造：别依赖 windowAround 本身的段内取
        assertEquals(w, repo.extendWindowOlder(me, conv, w)) // 本段 [900,905] 里没有更早的了；[1,10] 在缺口另一侧
    }

    /** 下滚：段内展开；到本段上沿原样返回（调用方据此去问服务端），不跨缺口拼后面的岛。 */
    @Test
    fun extendWindowNewerGrowsInsideTheSegmentAndStopsAtItsUpperEdge() = runBlocking {
        repo.onSyncPage(me, conv, (1L..10L).map { md(it) }, covered = 10)
        repo.onWindowPage(me, conv, (900L..905L).map { md(it) }) // 缺口 [11,899] 之后的另一岛
        val w = ChatWindow.Anchored(loTs = 1, loSeq = 1, hiTs = 3, hiSeq = 3)
        val grown = repo.extendWindowNewer(me, conv, w, page = 4)
        assertEquals(7L, grown.hiSeq) // 一页 = 4 条，段内
        val edge = repo.extendWindowNewer(me, conv, grown.copy(hiTs = 10, hiSeq = 10), page = 4)
        assertEquals(10L, edge.hiSeq) // 本段到 10 为止，不拼 900 那一岛
    }

    /** 查看器本地打底：只取点中那条所在段里的媒体，不拼缺口另一侧的旧岛（否则两段之间缺口里的图翻不到）。 */
    @Test
    fun conversationMediaInSegmentIgnoresTheIslandAcrossTheGap() = runBlocking {
        repo.onSyncPage(me, conv, (1L..10L).map { mdImg(it) }, covered = 10)        // 旧岛 [1,10] 全是图
        repo.onWindowPage(me, conv, (900L..905L).map { mdImg(it) })                  // 另一段 [900,905]
        val (items, segLo) = repo.conversationMediaInSegment(me, conv, 902)
        assertEquals((900L..905L).toList(), items.map { it.convSeq })
        assertEquals(900L, segLo)
        assertEquals(905L, repo.conversationMediaInSegment(me, conv, 902).hi)
        // 点中的不在任何段内：退回整条会话（segLo=0）
        assertEquals(0L, repo.conversationMediaInSegment(me, conv, 500).lo)
    }

    /** ↓N 读库那一半：10 万积压、本地只有最前 50 条——数出来必须是 10 万量级，不是已加载的 50。 */
    @Test
    fun unreadBelowFactsGiveTheBacklogCountNotTheLoadedRowCount() = runBlocking {
        repo.onSyncPage(me, conv, (1L..50L).map { md(it) }, covered = 50)
        repo.noteHead(me, conv, 100_000)
        val f = repo.unreadBelowFacts(me, conv, historyFloor = 0)
        assertEquals(100_000L, f.tip)
        assertEquals(50L, f.localNewest)
        val covered = SyncRanges.coversSpan(f.ranges, 11, f.tip)
        assertEquals(false, covered)
        assertEquals(99_990, UnreadBelow.count(f.tip, pendingRead = 10, loadedBelow = 40, covered = covered, localNewest = f.localNewest, floor = f.floor))
    }

    /** 清空过的会话：floor = 位点；读位点在位点以内也从位点数起，不把刚清掉的算成未读。 */
    @Test
    fun unreadBelowFactsAfterClearStartFromTheClearedFloor() = runBlocking {
        repo.onSyncPage(me, conv, (1L..30L).map { md(it) }, covered = 30)
        repo.clearConversation(me, conv)
        val f = repo.unreadBelowFacts(me, conv, historyFloor = 0)
        assertEquals(30L, f.floor)
        assertEquals(0, UnreadBelow.count(f.tip, pendingRead = 5, loadedBelow = 0, covered = false, localNewest = 0, floor = f.floor))
    }

    @Test
    fun windowAroundOnlyTakesTheTargetsOwnSegment() = runBlocking {
        repo.onSyncPage(me, conv, (1L..10L).map { md(it) }, covered = 10)
        repo.onWindowPage(me, conv, (900L..905L).map { md(it) })
        val w = repo.windowAround(me, conv, 900)!! // 前一页本该包含 [1,10] 里的行——不许
        val rows = repo.messages.observeRange(me, conv, w.loTs, w.loSeq, w.hiTs, w.hiSeq).first().map { it.convSeq }.sorted()
        assertEquals((900L..905L).toList(), rows)
    }

    /** 降级：有未读、读位点附近本地一条没有（服务端那一窗没取到）——不拿尾窗兜底，否则可见即读会越过缺口清掉未读。 */
    @Test
    fun farUnreadWithNothingLocalNearTheReadPositionHasNoSafeWindow() = runBlocking {
        repo.onSyncPage(me, conv, (1L..50L).map { md(it) }, covered = 50)
        repo.noteHead(me, conv, 100_000)
        repo.onIncoming(me, md(100_000), bumpUnread = false) // 离线期间实时进来的最新一条：孤岛，与读位点 1000 隔着缺口
        assertNull(repo.localEntryWindow(me, conv, readSeq = 1000, unread = 99_000))
    }

    /** 读位点落在清空位点以下：锚点从可见起点算起，被清掉的行不当「读位点之后第一条」。 */
    @Test
    fun entryAnchorStartsFromTheVisibleFloorWhenTheReadPositionIsBelowIt() = runBlocking {
        repo.onSyncPage(me, conv, (1L..30L).map { md(it) }, covered = 30)
        repo.noteHead(me, conv, 100_000)
        repo.clearConversation(me, conv)                                  // 位点 = 100_000？不：清空取本机所知最新，这里 head 已 100_000
        repo.onWindowPage(me, conv, (100_001L..100_200L).map { md(it) })   // 清空之后的新消息
        val w = repo.localEntryWindow(me, conv, readSeq = 5, unread = 150)
        val rows = when (w) {
            is ChatWindow.Anchored -> repo.messages.observeRange(me, conv, w.loTs, w.loSeq, w.hiTs, w.hiSeq).first().map { it.convSeq }
            is ChatWindow.Tail -> repo.observeTail(me, conv, w.fromSeq, w.limit).first().map { it.convSeq }
            null -> emptyList()
        }
        assertEquals(true, rows.isNotEmpty() && rows.all { it > 100_000 }) // 绝不露出位点以下的行
    }
}
