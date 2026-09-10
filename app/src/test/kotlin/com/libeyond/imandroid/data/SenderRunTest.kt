package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.SenderRun.Badge
import com.libeyond.imandroid.data.SenderRun.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 昵称截断、角色徽标、连续段判据（CHAT_UI_SKETCH §1.1，十七条对齐 #15）。 */
class SenderRunTest {

    @Test
    fun `十二个字以内原样显示`() {
        assertEquals("一二三四五六七八九十甲乙", SenderRun.clampName("一二三四五六七八九十甲乙"))
    }

    @Test
    fun `超过十二个字截断加省略号`() {
        assertEquals("一二三四五六七八九十甲乙…", SenderRun.clampName("一二三四五六七八九十甲乙丙"))
    }

    @Test
    fun `按字符簇数而不是 UTF-16 长度——emoji 不会被劈成半个`() {
        val twelve = "😀".repeat(12)                  // length 24，但只有 12 个字符
        assertEquals(twelve, SenderRun.clampName(twelve))
        assertEquals("😀".repeat(12) + "…", SenderRun.clampName("😀".repeat(13)))
        val accented = "é".repeat(13)            // e + 组合重音，一簇两个单元
        assertEquals("é".repeat(12) + "…", SenderRun.clampName(accented))
    }

    @Test
    fun `成员表的当前角色优先于消息上冻结的角色`() {
        assertEquals(Badge.Owner, SenderRun.badgeOf("owner", null))
        // 被撤掉管理员的人：旧消息 from_role 仍是 admin，成员表说 member → 不画
        assertNull(SenderRun.badgeOf("member", "admin"))
    }

    @Test
    fun `拿不到成员表时退回消息上的角色`() {
        assertEquals(Badge.Admin, SenderRun.badgeOf(null, "admin"))
        assertEquals(Badge.Admin, SenderRun.badgeOf("", "admin"))
        assertNull(SenderRun.badgeOf(null, null))
        assertNull(SenderRun.badgeOf(null, "member"))
    }

    @Test
    fun `同一人、普通消息才连成一段`() {
        assertTrue(SenderRun.sameRun(Item("a"), Item("a")))
        assertFalse(SenderRun.sameRun(Item("a"), Item("b")))
        assertFalse("系统消息断段", SenderRun.sameRun(Item("a", system = true), Item("a")))
        assertFalse("撤回断段", SenderRun.sameRun(Item("a"), Item("a", recalled = true)))
    }
}
