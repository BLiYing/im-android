package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选态的判据（M4-3 的另一半）。对端 iOS `IMChatViewController+Selection.m`
 * 与 im-web `messageContent.ts`/`selection.ts`，清单见 `docs/UI_PARITY_IOS.md` §4.7。
 *
 * 最要紧的一条钉在最后：**勾选态不吃行号、不吃窗口**——那正是 iOS 2026-09-06 那个
 * 「勾两条 → 上滚拉历史 → 前两条静默消失」的形状。
 */
class ChatSelectionTest {

    private fun msg(
        seq: Long,
        type: String = ContentType.TEXT,
        content: String = "hi",
        recalled: Long? = null,
        deleted: Long? = null,
        sender: String = "u1",
    ) = MessageEntity(
        ownerUid = "me", convId = "c1", convSeq = seq, sender = sender,
        contentType = type, content = content, timestamp = seq * 1000,
        recalledAt = recalled, deletedAt = deleted,
    )

    // ————————————————— 能不能勾 —————————————————

    @Test
    fun `系统提示 撤回墓碑 未确认的本地件都不可勾`() {
        assertTrue(ChatSelection.selectable(msg(1)))
        assertFalse("系统消息", ChatSelection.selectable(msg(2, type = ContentType.SYSTEM)))
        assertFalse("撤回墓碑", ChatSelection.selectable(msg(3, recalled = 111L)))
        // convSeq<=0 = 发送中/失败的本地件，服务端还没有它，转出去是空的
        assertFalse("未确认", ChatSelection.selectable(msg(0)))
        assertFalse("未确认", ChatSelection.selectable(msg(-1)))
    }

    @Test
    fun `能勾比能转发宽一档——空内容与已删除仍可勾，但转不出去`() {
        // 刻意分开：勾选是交互、转发是动作前复核。合成一条会让"勾了却少发几条"变成静默行为
        val emptyText = msg(4, content = "")
        val deleted = msg(5, deleted = 222L)
        assertTrue(ChatSelection.selectable(emptyText))
        assertTrue(ChatSelection.selectable(deleted))
        assertEquals(emptyList<MessageEntity>(), ChatSelection.forwardable(listOf(emptyText, deleted)))
    }

    // ————————————————— 勾选写入口 —————————————————

    @Test
    fun `勾一下加进来，再勾一下取消`() {
        val a = msg(1)
        val one = ChatSelection.toggle(emptyMap(), a)!!
        assertEquals(setOf(1L), one.keys)
        assertEquals(emptyMap<Long, MessageEntity>(), ChatSelection.toggle(one, a))
    }

    @Test
    fun `上限 100，满了再加要拒——绝不静默吞掉这一下点击`() {
        assertEquals(100, ChatSelection.MAX)
        val full = (1L..100L).associateWith { msg(it) }
        assertNull("满了再加要拒", ChatSelection.toggle(full, msg(101)))
        // **取消永远允许**：满了连取消都拒，用户就被卡死在"选满了又改不了"
        assertEquals(99, ChatSelection.toggle(full, msg(50))?.size)
    }

    @Test
    fun `点不可勾的行当没发生，不是拒也不是加`() {
        val one = ChatSelection.toggle(emptyMap(), msg(1))!!
        // 返回原集合（非 null=没触发超限吐司，也没把系统消息塞进去）
        assertEquals(one, ChatSelection.toggle(one, msg(2, type = ContentType.SYSTEM)))
    }

    // ————————————————— 导出顺序 —————————————————

    @Test
    fun `按 conv_seq 升序导出，不是按勾选先后`() {
        // 转发要按时序发、合并转发的条目也要按时序排——顺序是判据不是巧合
        var sel = ChatSelection.toggle(emptyMap(), msg(30))!!
        sel = ChatSelection.toggle(sel, msg(10))!!
        sel = ChatSelection.toggle(sel, msg(20))!!
        assertEquals(listOf(10L, 20L, 30L), ChatSelection.ordered(sel).map { it.convSeq })
    }

    // ————————————————— 文案 —————————————————

    @Test
    fun `标题按条数变，0 条时是「选择消息」`() {
        assertEquals("选择消息", ChatSelection.titleOf(0))
        assertEquals("已选择 1 条", ChatSelection.titleOf(1))
        assertEquals("已选择 100 条", ChatSelection.titleOf(100))
    }

    // ————————————————— 那条最贵的不变式 —————————————————

    @Test
    fun `勾选态按 conv_seq 记——窗口把消息裁掉了也还在，条数不缩水`() {
        // iOS 2026-09-06 的线上 bug：勾选态记在表格行选中里，向上翻页在头部插 N 条后
        // 行下标整体平移、reloadData 又清空选中，于是"勾两条→上滚拉历史→再勾一条"
        // 前两条静默消失。本端窗口化列表同样会把勾过的消息裁出内存，所以连实体一起存。
        var sel = ChatSelection.toggle(emptyMap(), msg(7))!!
        sel = ChatSelection.toggle(sel, msg(8))!!
        // 模拟：窗口翻页后这两条已不在渲染列表里——导出仍是 2 条，且拿得到实体本身
        val out = ChatSelection.ordered(sel)
        assertEquals(2, out.size)
        assertEquals(listOf(7L, 8L), out.map { it.convSeq })
        assertEquals("hi", out.first().content) // 不必回查数据库
    }

    // —— 多选删除给不给「为所有人删除」（2026-09-30，与 im-web selectDelete.ts / iOS 同口径）——

    @Test
    fun `全是我发的可以整批为所有人删除`() {
        val picked = listOf(msg(1, sender = "me"), msg(2, sender = "me"))
        assertTrue(ChatSelection.allDeletableForEveryone(picked, "me", isGroup = false, iAmManager = false))
    }

    @Test
    fun `混选了别人的一条就整批不给`() {
        val picked = listOf(msg(1, sender = "me"), msg(2, sender = "u2"), msg(3, sender = "me"))
        assertFalse(ChatSelection.allDeletableForEveryone(picked, "me", isGroup = false, iAmManager = false))
        // 群里的普通成员同样不给
        assertFalse(ChatSelection.allDeletableForEveryone(picked, "me", isGroup = true, iAmManager = false))
    }

    @Test
    fun `群主管理员可以整批删别人的消息 但单聊里没有管理员这回事`() {
        val picked = listOf(msg(1, sender = "u2"), msg(2, sender = "u3"))
        assertTrue(ChatSelection.allDeletableForEveryone(picked, "me", isGroup = true, iAmManager = true))
        assertFalse(ChatSelection.allDeletableForEveryone(picked, "me", isGroup = false, iAmManager = true))
    }

    @Test
    fun `空选与未落库的本地件不给`() {
        assertFalse(ChatSelection.allDeletableForEveryone(emptyList(), "me", isGroup = true, iAmManager = true))
        val withUnsent = listOf(msg(1, sender = "me"), msg(0, sender = "me"))
        assertFalse(ChatSelection.allDeletableForEveryone(withUnsent, "me", isGroup = false, iAmManager = false))
    }
}
