package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 单聊详情这条链的返回键。理由同 [GroupInfoNavTest]：同一个 bug 一天犯了三次。 */
class ChatDetailNavTest {

    @Test
    fun `层级由深到浅：媒体 大于 资料 大于 详情`() {
        assertEquals(ChatDetailPage.Media, ChatDetailNav.current(mediaOpen = true, profileOpen = true))
        assertEquals(ChatDetailPage.Profile, ChatDetailNav.current(mediaOpen = false, profileOpen = true))
        assertEquals(ChatDetailPage.Detail, ChatDetailNav.current(mediaOpen = false, profileOpen = false))
    }

    /**
     * 媒体**查看器不在这一层**——它归 ConvMediaHost 自己管。
     * 每个宿主只描述自己这一层，层级才不会互相渗透（枚举里出现 Viewer 就说明渗透了）。
     */
    @Test
    fun `查看器不属于这一层`() {
        assertEquals(3, ChatDetailPage.entries.size)
    }

    /**
     * `depth` 供 `PushTransition` 判转场方向（`ui/ChatDetailHost.kt`）：`Media`/`Profile` 都只从
     * `Detail` 直接进、互相之间没有真实跳转路径，深度同为 1——钉住这个约定，改错了转场方向会错。
     */
    @Test
    fun `Media 与 Profile 深度相同、都比 Detail 深一层`() {
        assertEquals(0, ChatDetailPage.Detail.depth)
        assertEquals(1, ChatDetailPage.Profile.depth)
        assertEquals(1, ChatDetailPage.Media.depth)
    }

    @Test
    fun `媒体与资料都退回详情`() {
        assertEquals(ChatDetailPage.Detail, ChatDetailNav.back(ChatDetailPage.Media))
        assertEquals(ChatDetailPage.Detail, ChatDetailNav.back(ChatDetailPage.Profile))
    }

    @Test
    fun `详情页返回是离开整条链`() {
        assertNull(ChatDetailNav.back(ChatDetailPage.Detail))
    }

    /** 新加一页却忘了给出路，这条会红——那正是「按返回直接退出整个 App」的成因。 */
    @Test
    fun `每一页的返回目标都有定义`() {
        for (p in ChatDetailPage.entries) {
            if (p == ChatDetailPage.Detail) continue
            assertEquals("$p 没有返回目标 → 按返回会直接退出整个 App", true, ChatDetailNav.back(p) != null)
        }
    }
}
