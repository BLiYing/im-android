package com.libeyond.imandroid.data

import com.imrtc.engine.IMCallHistoryMember
import com.imrtc.engine.IMCallHistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置 ▸ 最近通话」的纯判据（CALL_HISTORY_DESIGN.md §1/§3.5）。
 *
 * 这一族错了大多不报错，只是"看着不对"：未接判错是"明明打通了却标红"或"漏接来电没标红"，
 * 人数算错是"6 人的群通话显示 5 人"，翻页去重错是"同一条通话出现两次"，
 * 「未接」自动续页错是"切到未接 tab 只看到一条就再也不动"。
 */
class CallHistoryTest {

    private val me = "1001"

    private fun record(
        callId: String = "call-1",
        caller: String = me,
        durationSec: Int = 0,
        isGroup: Boolean = false,
        members: List<IMCallHistoryMember> = emptyList(),
        startedAtMs: Long = 1_000L,
    ) = IMCallHistoryRecord(
        callId = callId,
        roomId = "room-1",
        caller = caller,
        mediaType = "audio",
        isGroup = isGroup,
        reason = "hangup",
        endedBy = "",
        durationSec = durationSec,
        startedAtMs = startedAtMs,
        connectedAtMs = 0,
        endedAtMs = 0,
        userData = "",
        chatGroupId = "",
        members = members,
    )

    // ————————————————— 未接判定 —————————————————

    @Test
    fun `我是被叫且未接通才算未接`() {
        assertTrue(CallHistory.isMissed(record(caller = "2002", durationSec = 0), me))
    }

    @Test
    fun `我是被叫但接通了不算未接`() {
        assertFalse(CallHistory.isMissed(record(caller = "2002", durationSec = 30), me))
    }

    @Test
    fun `我是主叫不管有没有接通都不算未接`() {
        assertFalse(CallHistory.isMissed(record(caller = me, durationSec = 0), me))
        assertFalse(CallHistory.isMissed(record(caller = me, durationSec = 30), me))
    }

    // ————————————————— 1v1 对方 uid —————————————————

    @Test
    fun `我是被叫时对方是主叫`() {
        assertEquals("2002", CallHistory.peerUid(record(caller = "2002"), me))
    }

    @Test
    fun `我是主叫时对方是members里第一个不是我的人`() {
        val r = record(caller = me, members = listOf(IMCallHistoryMember(me, "accepted"), IMCallHistoryMember("3003", "no_answer")))
        assertEquals("3003", CallHistory.peerUid(r, me))
    }

    @Test
    fun `我是主叫但members里查不到对方时兜底空串`() {
        val r = record(caller = me, members = listOf(IMCallHistoryMember(me, "accepted")))
        assertEquals("", CallHistory.peerUid(r, me))
    }

    // ————————————————— 群通话人数 —————————————————

    @Test
    fun `发起人在members里时人数就是members条数`() {
        val r = record(
            isGroup = true, caller = me,
            members = listOf(IMCallHistoryMember(me, "accepted"), IMCallHistoryMember("2002", "accepted"), IMCallHistoryMember("3003", "no_answer")),
        )
        assertEquals(3, CallHistory.groupMemberCount(r))
    }

    @Test
    fun `发起人不在members里时要再加一个人`() {
        val r = record(isGroup = true, caller = me, members = listOf(IMCallHistoryMember("2002", "accepted"), IMCallHistoryMember("3003", "accepted")))
        assertEquals(3, CallHistory.groupMemberCount(r))
    }

    @Test
    fun `members为空时至少算1人`() {
        val r = record(isGroup = true, caller = me, members = emptyList())
        assertEquals(2, CallHistory.groupMemberCount(r)) // max(0,1) + 发起人不在里面的 1
    }

    // ————————————————— 翻页去重 —————————————————

    @Test
    fun `merge按callId去重`() {
        val loaded = listOf(record(callId = "a"), record(callId = "b"))
        val page = listOf(record(callId = "b"), record(callId = "c"))
        val merged = CallHistory.merge(loaded, page)
        assertEquals(listOf("a", "b", "c"), merged.map { it.callId })
    }

    // ————————————————— 未接 tab 自动续页 —————————————————

    @Test
    fun `已加载条数不够一屏且服务端还有下一页就该续拉`() {
        assertTrue(CallHistory.shouldAutoContinue(filteredCount = 3, hasMore = true))
    }

    @Test
    fun `已经凑够一屏就不用再拉`() {
        assertFalse(CallHistory.shouldAutoContinue(filteredCount = CallHistory.PAGE_SIZE, hasMore = true))
    }

    @Test
    fun `服务端没有下一页了不管够不够都不再拉`() {
        assertFalse(CallHistory.shouldAutoContinue(filteredCount = 0, hasMore = false))
    }

    @Test
    fun `未接筛选只保留我是被叫且未接通的记录`() {
        val records = listOf(
            record(callId = "missed", caller = "2002", durationSec = 0),
            record(callId = "answered", caller = "2002", durationSec = 10),
            record(callId = "outgoing", caller = me, durationSec = 0),
        )
        assertEquals(listOf("missed"), CallHistory.missedOnly(records, me).map { it.callId })
    }

    // ————————————————— 按天分组 —————————————————

    /** 假 labelOf：直接把时间戳当"天"用（同一个值 = 同一天），不依赖真实日历，只验证分组机制本身。 */
    private fun fakeLabel(ts: Long, now: Long): String = "day-$ts"

    @Test
    fun `连续同一天的记录合并进同一组`() {
        val records = listOf(
            record(callId = "1", startedAtMs = 1),
            record(callId = "2", startedAtMs = 1),
            record(callId = "3", startedAtMs = 2),
        )
        val groups = CallHistory.groupByDay(records, now = 0, labelOf = ::fakeLabel)
        assertEquals(listOf("day-1", "day-2"), groups.map { it.label })
        assertEquals(listOf("1", "2"), groups[0].records.map { it.callId })
        assertEquals(listOf("3"), groups[1].records.map { it.callId })
    }

    @Test
    fun `不连续但label相同不会被误合并成一组之外的情形也保持原有顺序`() {
        // groupByDay 只合并"相邻"的同 label 记录（调用方保证按时间倒序，不会出现旧记录夹在两组新记录中间）
        val records = listOf(record(callId = "1", startedAtMs = 5), record(callId = "2", startedAtMs = 5))
        val groups = CallHistory.groupByDay(records, now = 0, labelOf = ::fakeLabel)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].records.size)
    }

    @Test
    fun `空列表分组结果也是空列表`() {
        assertTrue(CallHistory.groupByDay(emptyList(), now = 0, labelOf = ::fakeLabel).isEmpty())
    }
}
