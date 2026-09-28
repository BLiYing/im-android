package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.screens.buildChatRows
import com.libeyond.imandroid.ui.screens.outgoingKeysOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天列表滚动判据（iOS `IMChatViewController+Scroll.m` 同口径，见 [ChatScroll]）。
 */
class ChatScrollTest {

    // —— 距底 ——

    @Test
    fun `最后一行不在视口里时距底不可知`() {
        assertNull(ChatScroll.distanceToBottomPx(totalRows = 50, lastVisibleIndex = 40, lastVisibleEnd = 900, viewportEnd = 1000, afterContentPadding = 8))
        assertFalse(ChatScroll.isNearBottom(null, 200f))
    }

    /**
     * 下内边距要算进去：到底时行尾停在「视口终点 − 下内边距」处。
     * 漏算的话贴底之后永远差这 3dp，收敛循环会一直判"没到底"。
     */
    @Test
    fun `到底时差值为零，下内边距要算进去`() {
        assertEquals(0, ChatScroll.distanceToBottomPx(10, 9, lastVisibleEnd = 992, viewportEnd = 1000, afterContentPadding = 8))
        assertEquals(300, ChatScroll.distanceToBottomPx(10, 9, lastVisibleEnd = 1292, viewportEnd = 1000, afterContentPadding = 8))
    }

    @Test
    fun `内容不满一屏或空列表都算在底`() {
        assertEquals(0, ChatScroll.distanceToBottomPx(3, 2, lastVisibleEnd = 400, viewportEnd = 1000, afterContentPadding = 8))
        assertEquals(0, ChatScroll.distanceToBottomPx(0, -1, 0, 1000, 8))
    }

    @Test
    fun `贴底阈值是严格小于`() {
        assertTrue(ChatScroll.isNearBottom(199, 200f))
        assertFalse(ChatScroll.isNearBottom(200, 200f))
    }

    // —— 键盘 / 面板改变视口高度 ——

    /** 判据是变化**前**贴不贴底；变化后再量必然"离底一个键盘高"。 */
    @Test
    fun `变化前贴着底才重贴`() {
        assertTrue(ChatScroll.shouldRestickOnResize(2000, 1400, wasNearBottom = true, userDragging = false))
        assertFalse(ChatScroll.shouldRestickOnResize(2000, 1400, wasNearBottom = false, userDragging = false))
    }

    @Test
    fun `手指按着列表时不和用户抢`() {
        assertFalse(ChatScroll.shouldRestickOnResize(2000, 1400, wasNearBottom = true, userDragging = true))
    }

    @Test
    fun `高度没变或首帧不算变化`() {
        assertFalse(ChatScroll.shouldRestickOnResize(1400, 1400, wasNearBottom = true, userDragging = false))
        assertFalse(ChatScroll.shouldRestickOnResize(-1, 1400, wasNearBottom = true, userDragging = false))
    }

    // —— 发送回底 ——

    @Test
    fun `出箱里新冒出一条才算刚发`() {
        assertTrue(ChatScroll.hasNewOutgoing(setOf("a"), setOf("a", "b")))
        // ack 回来那一行离开出箱：不是新发的
        assertFalse(ChatScroll.hasNewOutgoing(setOf("a", "b"), setOf("a")))
        // 重发复用原 clientMsgId：不是新发的
        assertFalse(ChatScroll.hasNewOutgoing(setOf("a"), setOf("a")))
    }

    /**
     * 出箱身份要逐格看宫格：一批图的第 2..N 张落进**同一个**宫格行，行数与行 key 都不变，
     * 只看行级的话发第二张图时认不出"刚发了一条"。
     */
    @Test
    fun `出箱身份取待发单条与宫格里还在发的格`() {
        fun p(cid: String, gid: String?, ts: Long) = PendingMessageEntity(
            ownerUid = "me", clientMsgId = cid, convId = "c1", to = "u2",
            contentType = ContentType.IMAGE, content = "content://x/$cid",
            groupId = gid, createdAt = ts,
        )
        val rows = buildChatRows(emptyList(), listOf(p("solo", null, 1), p("g-a", "g", 2), p("g-b", "g", 3)))
        assertEquals(setOf("solo", "g-a", "g-b"), outgoingKeysOf(rows))
    }

    /** 进会话时出箱里躺着的失败消息不是"刚发的"，不能一进来就被当成发送甩到底。 */
    @Test
    fun `基线未建时不判新`() {
        assertFalse(ChatScroll.hasNewOutgoing(null, setOf("failed-1")))
    }

    // —— 点空白收键盘 ——

    @Test
    fun `滑出 slop 或按到长按时长都不算轻点`() {
        assertTrue(ChatScroll.isTap(movedBeyondSlop = false, pressedMs = 120, longPressTimeoutMs = 400))
        assertFalse(ChatScroll.isTap(movedBeyondSlop = true, pressedMs = 120, longPressTimeoutMs = 400))
        assertFalse(ChatScroll.isTap(movedBeyondSlop = false, pressedMs = 400, longPressTimeoutMs = 400))
    }

    /** iOS `handleReplyJumpTap`：面板开着时这一下只收面板，不再落到气泡上。 */
    @Test
    fun `面板开着只收面板，否则收键盘`() {
        assertEquals(ChatScroll.TapAction.ClosePanelOnly, ChatScroll.tapActionOf(panelOpen = true))
        assertEquals(ChatScroll.TapAction.DismissKeyboard, ChatScroll.tapActionOf(panelOpen = false))
    }

    // —— 跳到某条后居中 ——

    /**
     * 2026-09-10 用户报的第 1 条：键盘收起时点回复条不跳。目标就在屏幕下半截，
     * `scrollToItem` 被列表尾部夹住、没到顶端；旧写法照「在顶端」往回滚半屏，把它推出了屏幕。
     */
    @Test
    fun `目标在屏幕下半截时往上补，不能往回滚`() {
        assertEquals(150, ChatScroll.centerDeltaPx(itemOffset = 900, itemSize = 100, viewportStart = 0, viewportEnd = 1600))
    }

    @Test
    fun `目标真在顶端时往回滚到中间`() {
        assertEquals(-450, ChatScroll.centerDeltaPx(itemOffset = 0, itemSize = 100, viewportStart = 0, viewportEnd = 1000))
    }

    @Test
    fun `比视口还高的行对齐它的顶`() {
        assertEquals(200, ChatScroll.centerDeltaPx(itemOffset = 200, itemSize = 1500, viewportStart = 0, viewportEnd = 1000))
    }

    /** 上内边距让视口起点为负：中线要按起止两端算，不能拿视口高度的一半。 */
    @Test
    fun `视口起点带内边距时按两端求中线`() {
        assertEquals(0, ChatScroll.centerDeltaPx(itemOffset = 450, itemSize = 100, viewportStart = -100, viewportEnd = 1100))
    }

    // —— 侧边滚动条 ——

    @Test
    fun `一屏放得下全部内容不画滚动条`() {
        assertNull(
            ChatScroll.scrollbarThumb(
                totalRows = 10, firstVisibleIndex = 0, firstVisibleOffset = 0,
                averageRowHeightPx = 80f, viewportHeightPx = 2000f,
            ),
        )
    }

    /** 滚到底时滑块该停在轨道最下端，不能因为像素取整之类的差一点点停不到底。 */
    @Test
    fun `滚到底时滑块贴着轨道底`() {
        // 100 行、平均行高 80px → 内容 8000px，视口 1000px，滑块高 = 1000*1000/8000 = 125px，可走 875px
        val thumb = ChatScroll.scrollbarThumb(
            totalRows = 100, firstVisibleIndex = 89, firstVisibleOffset = 0,
            averageRowHeightPx = 80f, viewportHeightPx = 1000f,
        )
        assertEquals(125f, thumb!!.heightPx, 0.01f)
        assertEquals(875f, thumb.topPx, 0.01f)
    }

    @Test
    fun `滚到顶时滑块贴着轨道顶`() {
        val thumb = ChatScroll.scrollbarThumb(
            totalRows = 100, firstVisibleIndex = 0, firstVisibleOffset = 0,
            averageRowHeightPx = 80f, viewportHeightPx = 1000f,
        )
        assertEquals(0f, thumb!!.topPx, 0.01f)
    }

    /** 内容特别长时按比例算出来的滑块会细到看不见，要托底一个最小高度。 */
    @Test
    fun `滑块不能细过最小高度`() {
        val thumb = ChatScroll.scrollbarThumb(
            totalRows = 100_000, firstVisibleIndex = 0, firstVisibleOffset = 0,
            averageRowHeightPx = 80f, viewportHeightPx = 1000f,
        )
        assertEquals(ChatScroll.SCROLLBAR_MIN_THUMB_PX, thumb!!.heightPx, 0.01f)
    }
}
