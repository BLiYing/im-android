package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 引用块与回复条上的名字（十七条对齐 #14/#15）：绝不退到 uid。 */
class ReplyNamesTest {

    @Test
    fun `引用自己显示「你」`() {
        assertEquals("你", ReplyNames.quoteFrom("me", "me", "备注", "群名", "昵称"))
    }

    @Test
    fun `被引用者按 本地名 群成员名 原消息昵称 的顺序取`() {
        assertEquals("备注", ReplyNames.quoteFrom("u1", "me", "备注", "群名", "昵称"))
        assertEquals("群名", ReplyNames.quoteFrom("u1", "me", null, "群名", "昵称"))
        assertEquals("昵称", ReplyNames.quoteFrom("u1", "me", "", null, "昵称"))
    }

    @Test
    fun `一个名字都没有时不画，绝不显示 uid`() {
        assertNull(ReplyNames.quoteFrom("7741990777", "me", null, null, null))
        assertNull(ReplyNames.quoteFrom("", "me", "备注", null, null))
        assertNull(ReplyNames.quoteFrom(null, "me", "备注", null, null))
    }

    @Test
    fun `回复条：引自己`() {
        assertEquals("回复 自己", ReplyNames.replyBarTitle("me", "me", true, "x", "y", "z", "群"))
    }

    @Test
    fun `回复条：群聊按名字优先级`() {
        assertEquals("回复 备注", ReplyNames.replyBarTitle("u1", "me", true, "备注", "群名", "昵称", "群"))
        assertEquals("回复 群名", ReplyNames.replyBarTitle("u1", "me", true, null, "群名", "昵称", "群"))
        assertEquals("回复 昵称", ReplyNames.replyBarTitle("u1", "me", true, null, null, "昵称", "群"))
        assertEquals("回复", ReplyNames.replyBarTitle("u1", "me", true, null, null, null, "群"))
    }

    @Test
    fun `回复条：单聊用会话标题`() {
        assertEquals("回复 老王", ReplyNames.replyBarTitle("u1", "me", false, null, null, "wang", "老王"))
    }
}
