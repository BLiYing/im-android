package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进会话定位（CHAT_UX §3）。与 iOS / Web 同口径，改判据要三端同时改。
 */
class ChatEntryTest {

    /**
     * 核心判据：**只认真实未读数**。
     * 写成 `latestSeq > readSeq` 对发送方必然成立（服务端未读排除本人消息），
     * Web 2026-09-03 因此让压测账号进会话被锚到一万条之前。
     */
    @Test
    fun `只认未读数不看序号差`() {
        assertTrue(ChatEntry.hasUnread(1))
        assertFalse(ChatEntry.hasUnread(0))
        // 自己刚发了一万条：latestSeq 远大于 readSeq，但 unread=0 → 必须贴底
        val seqs = (1L..50L).toList()
        assertEquals(49, ChatEntry.entryScrollIndex(seqs, readSeq = 1, unread = 0))
    }

    @Test
    fun `有未读则锚到首条未读本身（分割线对齐视口顶部）`() {
        val seqs = (1L..50L).toList()   // 下标 i 对应 seq i+1
        // readSeq=30 → 首条未读是 seq 31，下标 30；不再往上多带上下文（三端都是分割线贴顶）
        assertEquals(30, ChatEntry.entryScrollIndex(seqs, readSeq = 30, unread = 20))
    }

    /**
     * 分割线是独立的一行：目标必须是它，不是首条未读消息行。
     * 行序：[3, 日期(0), 分割线(0), 4(首条未读)]——锚到 4 的行（下标 3）分割线会落在视口之上。
     */
    @Test
    fun `有分割线行时锚到分割线本身`() {
        val seqs = listOf(3L, 0L, 0L, 4L, 5L)
        assertEquals(3, ChatEntry.entryScrollIndex(seqs, readSeq = 3, unread = 2))              // 不给分割线下标：回退到消息行
        assertEquals(2, ChatEntry.entryScrollIndex(seqs, readSeq = 3, unread = 2, dividerRow = 2)) // 给了：锚分割线
        // 无未读时分割线下标不起作用：贴底
        assertEquals(4, ChatEntry.entryScrollIndex(seqs, readSeq = 5, unread = 0, dividerRow = 2))
    }

    @Test
    fun `首条未读在开头时不越界`() {
        val seqs = (1L..50L).toList()
        assertEquals(0, ChatEntry.entryScrollIndex(seqs, readSeq = 0, unread = 50))
    }

    @Test
    fun `空列表不崩`() {
        assertEquals(0, ChatEntry.entryScrollIndex(emptyList(), readSeq = 0, unread = 5))
    }

    @Test
    fun `未读数大于零但本地没有更新的消息时贴底`() {
        val seqs = listOf(1L, 2L, 3L)
        assertEquals(2, ChatEntry.entryScrollIndex(seqs, readSeq = 10, unread = 5))
    }

    /** 非消息行（日期胶囊/待发消息）seq=0，不能被当成首条未读。 */
    @Test
    fun `seq 为零的行不参与首条未读判定`() {
        val seqs = listOf(0L, 1L, 0L, 2L, 3L)
        // readSeq=1 → 首条未读是 seq 2，下标 3
        assertEquals(3, ChatEntry.unreadDividerIndex(seqs, readSeq = 1, unread = 2))
    }

    @Test
    fun `无未读不画分割线`() {
        assertEquals(-1, ChatEntry.unreadDividerIndex(listOf(1L, 2L), readSeq = 2, unread = 0))
    }

    // ——— 自动贴底 ———

    @Test
    fun `贴着底才自动滚`() {
        assertTrue(ChatEntry.shouldAutoScroll(lastVisibleIndex = 49, totalRows = 50))
        assertTrue(ChatEntry.shouldAutoScroll(lastVisibleIndex = 47, totalRows = 50))
        assertFalse("正在翻历史时不该被拽回底部", ChatEntry.shouldAutoScroll(20, 50))
    }

    /**
     * 列表尚未测量（首次组合）不算贴底——否则会把「进会话停在首条未读」当场覆盖掉。
     * 这两条路必须分开：首屏定位归 entryScrollIndex，后续贴底归 shouldAutoScroll。
     */
    @Test
    fun `未测量时不自动滚`() {
        assertFalse(ChatEntry.shouldAutoScroll(lastVisibleIndex = -1, totalRows = 50))
    }
}
