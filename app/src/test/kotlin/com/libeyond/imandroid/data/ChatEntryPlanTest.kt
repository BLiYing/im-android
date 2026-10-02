package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用例对应 im-web `windowPlan.test.ts` 里 `planEntryWindow` 那组（对称的是不变式）。 */
class ChatEntryPlanTest {
    private fun r(lo: Long, hi: Long) = SeqRange(lo, hi)

    private fun plan(
        readSeq: Long = 0, unread: Int = 0, tip: Long = 1000, ranges: List<SeqRange> = emptyList(),
        localNewest: Long = 1000, floor: Long = 0,
    ) = ChatEntryPlan.plan(readSeq, unread, tip, ranges, localNewest, floor, before = 25, after = 100, latestPage = 100)

    @Test
    fun `有未读锚到已读位点 无未读取最新`() {
        assertEquals(500L, ChatEntryPlan.anchorFor(readSeq = 500, unread = 3))
        assertEquals(0L, ChatEntryPlan.anchorFor(readSeq = 500, unread = 0))
    }

    @Test
    fun `有未读时锚点最小是 1 不是 0（0 是取最新哨兵）`() {
        assertEquals(1L, ChatEntryPlan.anchorFor(readSeq = 0, unread = 100_000))
        val p = plan(readSeq = 0, unread = 100_000, ranges = emptyList()) as EntryPlan.Server
        assertEquals(1L, p.anchor)
        assertEquals(25, p.before)
        assertEquals(100, p.after)
    }

    @Test
    fun `没有未读就取最新 anchor 为 0`() {
        val p = plan(unread = 0, ranges = emptyList()) as EntryPlan.Server
        assertEquals(0L, p.anchor)
        assertEquals(100, p.before)
        assertEquals(0, p.after)
    }

    @Test
    fun `tip 未知一律问服务端`() {
        assertEquals(EntryPlan.Server(0, 100, 0), plan(tip = 0, ranges = listOf(r(1, 1000))))
    }

    @Test
    fun `最新页被同一段覆盖且手里有东西才走本地`() {
        assertEquals(EntryPlan.Local, plan(tip = 1000, ranges = listOf(r(901, 1000))))
        // 下沿是 tip-before+1=901：[900,1000] 的段当然也盖得住；[902,1000] 盖不住
        assertEquals(EntryPlan.Server(0, 100, 0), plan(tip = 1000, ranges = listOf(r(902, 1000))))
    }

    @Test
    fun `缺口 有两段但最新页那段不够 就问服务端`() {
        assertEquals(EntryPlan.Server(0, 100, 0), plan(tip = 1000, ranges = listOf(r(1, 300), r(950, 1000))))
    }

    @Test
    fun `清单说齐全但本地一条没有 也要问服务端`() {
        assertEquals(EntryPlan.Server(0, 100, 0), plan(tip = 1000, ranges = listOf(r(1, 1000)), localNewest = 0))
    }

    @Test
    fun `锚点开窗的下沿是 anchor-before 上沿是 anchor+after 夹到 tip`() {
        // 读位点 500，窗口 [475, 600]
        assertEquals(EntryPlan.Local, plan(readSeq = 500, unread = 10, ranges = listOf(r(400, 700))))
        assertEquals(EntryPlan.Server(500, 25, 100), plan(readSeq = 500, unread = 10, ranges = listOf(r(480, 700))))
        // 靠近 tip：上沿夹到 tip
        assertEquals(EntryPlan.Local, plan(readSeq = 990, unread = 10, tip = 1000, ranges = listOf(r(900, 1000))))
    }

    @Test
    fun `会话最新位点在下界以内 可见范围内一条没有 直接本地 不许问服务端`() {
        assertEquals(EntryPlan.Local, plan(tip = 30, floor = 30, localNewest = 0, ranges = emptyList()))
        assertEquals(EntryPlan.Local, plan(tip = 30, floor = 40, localNewest = 0))
    }

    @Test
    fun `读位点落在被清掉的那段里 锚点抬到下界之上 且不再带上下文`() {
        val p = plan(readSeq = 5, unread = 25, tip = 1000, floor = 30, ranges = emptyList(), localNewest = 0) as EntryPlan.Server
        assertEquals(31L, p.anchor)
        assertEquals(0, p.before)
    }

    @Test
    fun `下沿夹到 floor+1 清单只需覆盖下界之上`() {
        // 最新页 [901,1000] 全在下界之上；段从 951 起也要判不齐
        assertEquals(EntryPlan.Server(0, 100, 0), plan(tip = 1000, floor = 0, ranges = listOf(r(951, 1000))))
        // 下界 950：下沿夹到 951，段 [951,1000] 就够了
        assertEquals(EntryPlan.Local, plan(tip = 1000, floor = 950, ranges = listOf(r(951, 1000))))
    }
}
