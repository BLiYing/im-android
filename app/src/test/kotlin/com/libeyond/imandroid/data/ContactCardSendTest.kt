package com.libeyond.imandroid.data

import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 发名片多选上限 / 确认标题 / 折叠摘要（对齐 iOS `IMChatViewController+Contact.m`）。 */
class ContactCardSendTest {

    @After
    fun restoreChinese() = Str.install(XmlStringResolver())

    @Test
    fun `上限与 iOS kIMContactMaxSelection 同为 9`() {
        assertEquals(9, ContactCardSend.MAX_SELECTION)
    }

    @Test
    fun `normalize 去重丢空并截到上限，保持勾选顺序`() {
        val raw = listOf("c", "", "a", "c", "b") + (1..20).map { "u$it" }
        val out = ContactCardSend.normalize(raw)
        assertEquals(9, out.size)
        assertEquals(listOf("c", "a", "b", "u1", "u2", "u3", "u4", "u5", "u6"), out)
    }

    @Test
    fun `一张时没有摘要行`() {
        assertEquals("", ContactCardSend.restSummary(listOf("小红")))
        assertEquals("", ContactCardSend.restSummary(emptyList()))
    }

    @Test
    fun `首张之外最多列 3 个名字，用点号相连`() {
        assertEquals("老王", ContactCardSend.restSummary(listOf("小红", "老王")))
        assertEquals("老王 · 小刚 · 阿花", ContactCardSend.restSummary(listOf("小红", "老王", "小刚", "阿花")))
    }

    @Test
    fun `超过 3 个追加「等 N 人」，N 是首张之外的总人数`() {
        val names = listOf("甲", "乙", "丙", "丁", "戊", "己")
        assertEquals("乙 · 丙 · 丁 等 5 人", ContactCardSend.restSummary(names))
        Str.install(XmlStringResolver("en"))
        assertEquals("乙 · 丙 · 丁 and 5 more", ContactCardSend.restSummary(names))
    }

    @Test
    fun `标题单张与多张分开，名字带在里面`() {
        assertEquals("发送名片给「技术群」", ContactCardSend.title(1, "技术群"))
        assertEquals("发送 3 张名片给「技术群」", ContactCardSend.title(3, "技术群"))
        Str.install(XmlStringResolver("en"))
        assertEquals("Send contact card to \"Bob\"", ContactCardSend.title(1, "Bob"))
        assertEquals("Send 9 contact cards to \"Bob\"", ContactCardSend.title(9, "Bob"))
    }
}
