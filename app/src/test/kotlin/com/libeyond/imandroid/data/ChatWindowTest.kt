package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染窗口（`../IMServer/docs/design/MESSAGE_WINDOW_DESIGN.md` §4）。
 *
 * ### 为什么这两条判据要单测
 * 分页这一族的失败方式是**功能还在、界面照常、结果悄悄不对**——
 * 边界算错就少几行/多几行，跳转落在别处；「回到最新」判据写窄了，用户跳完就回不去了。
 * 后一条 iOS 与 Web **都栽过**（`CLIENT_PARITY.md` 那条「跨窗口定位一次到位 + 有回程」的第 ③ 点：
 * 跳转不产生滚动事件，而且跳过去的那一段常常整屏放得下，连"离底很远"的兜底都轮不到）。
 */
class ChatWindowTest {

    private fun p(ts: Long, seq: Long) = SeqPoint(ts, seq)

    // ————————————————— 窗口边界 —————————————————

    @Test
    fun `边界取两侧最远的那一行`() {
        // before 是倒序（近→远），所以最远的是 last；atOrAfter 是正序，最远的是 last
        val w = ChatWindows.boundsOf(
            before = listOf(p(90, 9), p(80, 8), p(70, 7)),
            atOrAfter = listOf(p(100, 10), p(110, 11)),
        )
        assertEquals(ChatWindow.Anchored(loTs = 70, loSeq = 7, hiTs = 110, hiSeq = 11), w)
    }

    @Test
    fun `锚点就是会话最早那条——下界取它自己，不是回 null`() {
        // 一侧空是**正常**的（锚点是最早/最新那条）。判成 null 会让"跳到第一条"永远失败。
        val w = ChatWindows.boundsOf(before = emptyList(), atOrAfter = listOf(p(100, 10), p(110, 11)))
        assertEquals(ChatWindow.Anchored(loTs = 100, loSeq = 10, hiTs = 110, hiSeq = 11), w)
    }

    @Test
    fun `锚点就是最新那条——上界取它自己`() {
        val w = ChatWindows.boundsOf(before = listOf(p(90, 9), p(80, 8)), atOrAfter = emptyList())
        assertEquals(ChatWindow.Anchored(loTs = 80, loSeq = 8, hiTs = 90, hiSeq = 9), w)
    }

    @Test
    fun `两侧都空才是真的没有——回 null 让调用方去问服务端`() {
        assertNull(ChatWindows.boundsOf(before = emptyList(), atOrAfter = emptyList()))
    }

    @Test
    fun `只有一条消息时窗口退化成它自己`() {
        val w = ChatWindows.boundsOf(before = emptyList(), atOrAfter = listOf(p(100, 10)))
        assertEquals(ChatWindow.Anchored(100, 10, 100, 10), w)
    }

    // ————————————————— 「回到最新」按钮 —————————————————

    @Test
    fun `窗口停在历史时必须亮——哪怕整屏都看得见底`() {
        // 这是最容易漏的一条：跳过去的那一段常常整屏放得下，awayFromBottom 恒为 false，
        // 不判窗口态的话按钮永远不出现，用户跳完回不去。
        val anchored = ChatWindow.Anchored(1, 1, 2, 2)
        assertTrue(ChatWindows.showsJumpToLatest(anchored, awayFromBottom = false))
        assertTrue(ChatWindows.showsJumpToLatest(anchored, awayFromBottom = true))
    }

    @Test
    fun `尾窗里按既有判据——贴着底就不亮`() {
        val tail = ChatWindow.Tail(200)
        assertFalse(ChatWindows.showsJumpToLatest(tail, awayFromBottom = false))
        assertTrue(ChatWindows.showsJumpToLatest(tail, awayFromBottom = true))
    }

    @Test
    fun `尾窗与锚点窗是两种态，别混`() {
        assertTrue(ChatWindow.Tail(200).isTail)
        assertFalse(ChatWindow.Anchored(1, 1, 2, 2).isTail)
    }
}
