package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.ChatFolder
import com.libeyond.imandroid.data.FolderLimits
import com.libeyond.imandroid.data.FolderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话长按菜单里的文件夹项（设计 §4.4）：顺序 / 已入夹 / 多夹 / 无文件夹 / 已满。
 * 文案走 `Str.s`（需要 Android 资源）→ 这里只断言**结构**（条数、是否带子菜单、点击后调到哪个回调），不断言文案。
 */
class ConversationFolderMenuTest {
    private fun f(id: String, vararg convs: String) = ChatFolder(id, id, 0, convs.toList())

    private class Calls {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        var newCount = 0
        val toasts = mutableListOf<String>()
    }

    private fun items(snap: FolderSnapshot, conv: String, current: String?, calls: Calls) =
        conversationFolderItems(snap, conv, current, { f, m -> calls.toggles += f.id to m }, { calls.newCount++ }, { calls.toasts += it })

    @Test
    fun `没有任何文件夹时一条直达新建`() {
        val c = Calls()
        val it = items(FolderSnapshot(), "c1", null, c)
        assertEquals(1, it.size)
        assertTrue(it[0].submenu.isEmpty())
        it[0].onClick()
        assertEquals(1, c.newCount)
    }

    @Test
    fun `未入夹只有添加项且带二级`() {
        val c = Calls()
        val it = items(FolderSnapshot(1, listOf(f("A"), f("B"))), "c1", null, c)
        assertEquals(1, it.size)
        assertEquals(listOf(false, false, false), it[0].submenu.map { false }.let { listOf(false, false, false) }) // 2 夹 + 新建
        assertEquals(3, it[0].submenu.size)
        it[0].submenu[1].onClick() // 点 B = 加入
        assertEquals(listOf("B" to true), c.toggles)
    }

    @Test
    fun `已入夹恰在一个夹内 移出排第一且一步完成 其后仍有添加`() {
        val c = Calls()
        val it = items(FolderSnapshot(1, listOf(f("A", "c1"), f("B"))), "c1", null, c)
        assertEquals(2, it.size)
        assertTrue("第一项是一步移出，不带二级", it[0].submenu.isEmpty())
        it[0].onClick()
        assertEquals(listOf("A" to false), c.toggles)
        assertTrue("第二项是添加（带二级）", it[1].submenu.isNotEmpty())
    }

    @Test
    fun `在文件夹 Tab 内查看时移出的是当前夹`() {
        val c = Calls()
        val it = items(FolderSnapshot(1, listOf(f("A", "c1"), f("B", "c1"))), "c1", "B", c)
        it[0].onClick()
        assertEquals(listOf("B" to false), c.toggles)
    }

    @Test
    fun `在多个夹内且在全部里 移出带二级只列所在夹`() {
        val c = Calls()
        val it = items(FolderSnapshot(1, listOf(f("A", "c1"), f("B", "c1"), f("C"))), "c1", null, c)
        assertEquals(2, it.size)
        assertEquals(2, it[0].submenu.size)
        it[0].submenu[1].onClick()
        assertEquals(listOf("B" to false), c.toggles)
    }

    @Test
    fun `已满的夹点了只提示 不发请求 文件夹数满时新建只提示`() {
        val c = Calls()
        val snap = FolderSnapshot(
            1, listOf(f("A", *Array(2) { "x$it" }), f("B")), FolderLimits(maxFolders = 2, maxConvs = 2),
        )
        val it = items(snap, "c1", null, c)
        val sub = it[0].submenu
        sub[0].onClick() // A 已满
        sub.last().onClick() // 新建：文件夹数已满
        assertTrue(c.toggles.isEmpty())
        assertEquals(0, c.newCount)
        assertEquals(2, c.toasts.size)
    }
}
