package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * push 转场方向与底部 Tab 栏。**存在的理由**：二级页曾一直挂着底栏（2026-09-15 用户报）——
 * 新加一个二级页若忘了给深度、或误写成 0，这里当场红。
 */
class PushNavTest {

    @Test
    fun `变深是前进、变浅是后退`() {
        assertEquals(PushDirection.Forward, PushNav.directionOf(fromDepth = 0, toDepth = 1))
        assertEquals(PushDirection.Forward, PushNav.directionOf(fromDepth = 1, toDepth = 2))
        assertEquals(PushDirection.Back, PushNav.directionOf(fromDepth = 1, toDepth = 0))
        // 建群成功直接回通讯录列表：跨两层也是后退
        assertEquals(PushDirection.Back, PushNav.directionOf(fromDepth = 2, toDepth = 0))
    }

    @Test
    fun `同深度换内容按前进——群资料里点成员发消息换会话，新聊天页该从右边盖进来`() {
        assertEquals(PushDirection.Forward, PushNav.directionOf(fromDepth = 1, toDepth = 1))
    }

    @Test
    fun `只有根页画底部 Tab 栏`() {
        assertTrue(PushNav.showsTabBar(PushNav.ROOT_DEPTH))
        assertFalse(PushNav.showsTabBar(1))
        assertFalse(PushNav.showsTabBar(2))
    }

    @Test
    fun `通讯录只有列表页是根页——新的朋友 找人 群聊 资料 建群一律不画底栏`() {
        assertEquals(listOf(ContactsPage.List), ContactsPage.entries.filter { PushNav.showsTabBar(it.depth) })
    }

    @Test
    fun `消息页只有会话列表是根页——右上角加号推出去的添加朋友 新建群聊不画底栏`() {
        assertEquals(listOf(ChatsPage.List), ChatsPage.entries.filter { PushNav.showsTabBar(it.depth) })
    }

    @Test
    fun `我页只有列表页是根页——资料 二维码 设备 数据和存储 隐私与安全一律不画底栏`() {
        assertEquals(listOf(MePage.List), MePage.entries.filter { PushNav.showsTabBar(it.depth) })
    }

    @Test
    fun `建群从群聊列表点进去，比群聊列表深一层——从它退回群聊列表才是后退`() {
        assertTrue(ContactsPage.CreateGroup.depth > ContactsPage.Groups.depth)
    }
}
