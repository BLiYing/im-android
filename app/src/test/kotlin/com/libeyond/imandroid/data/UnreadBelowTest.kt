package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadBelowTest {
    @Test
    fun `区间清单盖住就数本地——尾部那条事件行不凭空多出一条未读`() {
        // 最后一个 conv_seq 是 op=pin 事件行：tip=110031 > localNewest=110030，但清单盖住了 → 精确值
        assertEquals(0, UnreadBelow.count(tip = 110031, pendingRead = 110030, loadedBelow = 0, covered = true, localNewest = 110030, floor = 0))
    }

    @Test
    fun `没盖住且 tip 超过本地最新——按 tip 减读位点，不是窗口条数`() {
        // 10 万积压：窗口里只有 89 行，↓N 必须是 10 万量级，不是 89
        assertEquals(99_900, UnreadBelow.count(tip = 100_000, pendingRead = 100, loadedBelow = 89, covered = false, localNewest = 300, floor = 0))
    }

    @Test
    fun `tip 未知退回数本地`() {
        assertEquals(7, UnreadBelow.count(tip = 0, pendingRead = 10, loadedBelow = 7, covered = false, localNewest = 50, floor = 0))
    }

    @Test
    fun `没盖住但 tip 不超过本地最新——数本地`() {
        assertEquals(5, UnreadBelow.count(tip = 100, pendingRead = 10, loadedBelow = 5, covered = false, localNewest = 100, floor = 0))
    }

    @Test
    fun `清空之后读位点在位点以内——从下界数起`() {
        // 清空位点 5000（floor=5000），读位点还停在 4000，之后本机新收 3 条：不能把 5000 以内的算成未读
        assertEquals(3, UnreadBelow.count(tip = 5003, pendingRead = 4000, loadedBelow = 3, covered = true, localNewest = 5003, floor = 5000))
        assertEquals(3, UnreadBelow.count(tip = 5003, pendingRead = 4000, loadedBelow = 3, covered = false, localNewest = 4990, floor = 5000))
    }

    @Test
    fun `可见范围内一条没有——清空后切回来不显示未读`() {
        assertEquals(0, UnreadBelow.count(tip = 5000, pendingRead = 100, loadedBelow = 0, covered = false, localNewest = 0, floor = 5000))
    }

    @Test
    fun `读位点已到 tip 时近似值不为负`() {
        assertEquals(0, UnreadBelow.count(tip = 100, pendingRead = 120, loadedBelow = 0, covered = false, localNewest = 90, floor = 0))
    }
}
