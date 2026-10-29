package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 空态是结论：两条证据都在才画「还没有会话」（冷启动/登录先闪一下空态，2026-09-15 用户报）。 */
class ConversationListPhaseTest {

    @Test
    fun `本地库还没回第一份——什么都不画，哪怕服务端说过没有`() {
        assertEquals(ConversationListPhase.Loading, ConversationListPhase.of(localCount = null, serverCount = null))
        assertEquals(ConversationListPhase.Loading, ConversationListPhase.of(localCount = null, serverCount = 0))
    }

    @Test
    fun `本地有就画列表，不等服务端`() {
        assertEquals(ConversationListPhase.List, ConversationListPhase.of(localCount = 3, serverCount = null))
        assertEquals(ConversationListPhase.List, ConversationListPhase.of(localCount = 3, serverCount = 0))
    }

    @Test
    fun `本地空但服务端还没拉成——新装包首登的那段等待不是空态`() {
        assertEquals(ConversationListPhase.Loading, ConversationListPhase.of(localCount = 0, serverCount = null))
    }

    @Test
    fun `服务端说有而库还没回写——拉取完成与列表出现之间那一缝不闪空态`() {
        assertEquals(ConversationListPhase.Loading, ConversationListPhase.of(localCount = 0, serverCount = 5))
    }

    @Test
    fun `本地空且服务端也说没有——才是空态`() {
        assertEquals(ConversationListPhase.Empty, ConversationListPhase.of(localCount = 0, serverCount = 0))
    }

    @Test
    fun `别端清除全部会话后，服务端计数减到 0 才出空态，不会一直空白加载`() {
        var listed = ListedConversations("me", 2)
        assertEquals(ConversationListPhase.Loading, ConversationListPhase.of(0, listed.count)) // 本地已空、旧结论还说有 2 条
        listed = listed.afterRemoteDelete().afterRemoteDelete()
        assertEquals(ConversationListPhase.Empty, ConversationListPhase.of(0, listed.count))
        assertEquals(0, listed.afterRemoteDelete().count) // 多余的帧不会减成负数
    }
}
