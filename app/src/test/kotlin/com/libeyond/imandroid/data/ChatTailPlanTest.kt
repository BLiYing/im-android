package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用例对应 iOS `IMChatWindowPlanTests` / Web `windowPlan.test.ts` 里最新页那组（对称的是不变式）。 */
class ChatTailPlanTest {

    @Test
    fun `最新页下沿是 tip-page+1 不是 tip-page`() {
        // window_req(anchor=0, before=100) 拿回的是 [tip-99, tip]；多算一格会让刚取回的一页永远判不齐
        assertEquals(901L, ChatTailPlan.latestPageLow(1000, 100))
        assertEquals(1L, ChatTailPlan.latestPageLow(50, 100)) // 会话不足一页：夹到 1
    }

    @Test
    fun `下沿不低于有效可见起点`() {
        assertEquals(951L, ChatTailPlan.latestPageLowAboveFloor(1000, 100, visibleFrom = 951))
        assertEquals(901L, ChatTailPlan.latestPageLowAboveFloor(1000, 100, visibleFrom = 0))
    }

    @Test
    fun `tip 取 head 与列表最新位点的大者`() {
        assertEquals(300L, ChatTailPlan.tip(head = 300, lastConvSeq = 120))
        assertEquals(120L, ChatTailPlan.tip(head = 0, lastConvSeq = 120))
        assertEquals(0L, ChatTailPlan.tip(0, 0))
    }

    @Test
    fun `可见起点 = 清空位点 + 1`() {
        assertEquals(0L, ChatTailPlan.visibleFrom(0))
        assertEquals(31L, ChatTailPlan.visibleFrom(30))
    }

    @Test
    fun `最新页没被同一段覆盖才问服务端`() {
        assertTrue(ChatTailPlan.shouldRequestTail(tip = 1000, covered = false, windowTailHi = 500, visibleFrom = 0))
        assertFalse(ChatTailPlan.shouldRequestTail(tip = 1000, covered = true, windowTailHi = 1000, visibleFrom = 0))
    }

    @Test
    fun `tip 未知 空窗必须问 有内容不白跑`() {
        assertTrue(ChatTailPlan.shouldRequestTail(tip = 0, covered = false, windowTailHi = 0, visibleFrom = 0))
        assertFalse(ChatTailPlan.shouldRequestTail(tip = 0, covered = false, windowTailHi = 40, visibleFrom = 0))
    }

    @Test
    fun `可见范围内一条没有就不问 清空过的会话尤其如此`() {
        assertFalse(ChatTailPlan.shouldRequestTail(tip = 30, covered = false, windowTailHi = 0, visibleFrom = 31))
    }

    @Test
    fun `bump 只有贴底才补 翻历史不补`() {
        assertTrue(ChatTailPlan.bumpShouldCatchUp(following = true, head = 500, tailHi = 300))
        assertFalse(ChatTailPlan.bumpShouldCatchUp(following = false, head = 500, tailHi = 300))
    }

    @Test
    fun `bump 信号晚到 窗口已含最新就不补`() {
        assertFalse(ChatTailPlan.bumpShouldCatchUp(following = true, head = 300, tailHi = 300))
        assertFalse(ChatTailPlan.bumpShouldCatchUp(following = true, head = 0, tailHi = 0))
    }

    @Test
    fun `有效可见起点取清空位点之后与服务端下界的大者`() {
        assertEquals(31L, ChatTailPlan.visibleFrom(clearedUpTo = 30, historyFloor = 10))
        assertEquals(500L, ChatTailPlan.visibleFrom(clearedUpTo = 30, historyFloor = 500))
        assertEquals(0L, ChatTailPlan.visibleFrom(0, 0))
    }

    @Test
    fun `下界记本窗留下的最小 seq 一条没留就退回锚点`() {
        assertEquals(120L, ChatTailPlan.floorFromWindow(minKeptSeq = 120, anchor = 500))
        assertEquals(500L, ChatTailPlan.floorFromWindow(minKeptSeq = 0, anchor = 500))
        assertEquals(0L, ChatTailPlan.floorFromWindow(0, 0))
    }

    @Test
    fun `下界只往小里收 未知不参与`() {
        assertEquals(100L, ChatTailPlan.mergeHistoryFloor(current = 300, incoming = 100))
        assertEquals(100L, ChatTailPlan.mergeHistoryFloor(current = 100, incoming = 300)) // 不被更大的值抬高
        assertEquals(300L, ChatTailPlan.mergeHistoryFloor(current = 0, incoming = 300))
        assertEquals(300L, ChatTailPlan.mergeHistoryFloor(current = 300, incoming = 0))
    }

    @Test
    fun `上面还有没有 到了可见起点就没有`() {
        assertTrue(ChatTailPlan.hasMoreAbove(oldestRendered = 50, visibleFrom = 0))
        assertFalse(ChatTailPlan.hasMoreAbove(oldestRendered = 1, visibleFrom = 0))
        assertFalse(ChatTailPlan.hasMoreAbove(oldestRendered = 31, visibleFrom = 31)) // 踩在清空位点之后第一条上
        assertTrue(ChatTailPlan.hasMoreAbove(oldestRendered = 32, visibleFrom = 31))
        assertFalse(ChatTailPlan.hasMoreAbove(oldestRendered = 0, visibleFrom = 0)) // 全是待发消息
    }
}
