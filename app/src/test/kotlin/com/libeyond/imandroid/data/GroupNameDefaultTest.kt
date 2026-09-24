package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 建群默认群名规则（对端 iOS `Common/IMGroupNameDefault.m`）——判据逐字同源，见该文件头注释。
 */
class GroupNameDefaultTest {

    @Test
    fun `rune 数把代理对算 1，与服务端 len(rune) 同口径`() {
        // "𠀀"（U+20000，扩展 B）是一个代理对，UTF-16 length=2，rune 数应为 1
        assertEquals(1, GroupNameDefault.runeLength("𠀀"))
        assertEquals(3, GroupNameDefault.runeLength("abc"))
        assertEquals(0, GroupNameDefault.runeLength(""))
    }

    @Test
    fun `截断不切碎代理对`() {
        val s = "ab𠀀cd" // a b 𠀀 c d，5 个 rune
        assertEquals("ab𠀀", GroupNameDefault.truncateToRunes(s, 3))
        assertEquals(s, GroupNameDefault.truncateToRunes(s, 10)) // 不超长原样返回
        assertEquals("", GroupNameDefault.truncateToRunes(s, 0))
    }

    @Test
    fun `公开名——昵称优先，其次 at username，最后内部 ID`() {
        assertEquals("小明", GroupNameDefault.publicUserName("小明", "user123", "u1"))
        assertEquals("@user123", GroupNameDefault.publicUserName("", "user123", "u1"))
        assertEquals("@user123", GroupNameDefault.publicUserName(null, "user123", "u1"))
        assertEquals("u1", GroupNameDefault.publicUserName("", "", "u1"))
        assertEquals("u1", GroupNameDefault.publicUserName("  ", null, "u1"))
    }

    @Test
    fun `默认群名按顺序拼接，放不下就到此为止`() {
        assertEquals("我、小明、小红", GroupNameDefault.defaultName(listOf("我", "小明", "小红")))
        // 空名跳过，不产生多余顿号
        assertEquals("我、小红", GroupNameDefault.defaultName(listOf("我", "", "小红")))
    }

    @Test
    fun `超过上限时整体截断到 30 rune，不切碎最后一个名字`() {
        val names = (1..20).map { "用户名字很长很长很长$it" }
        val result = GroupNameDefault.defaultName(names, maxLen = 30)
        assertEquals(GroupNameDefault.MAX_LENGTH.coerceAtMost(30), 30)
        assert(GroupNameDefault.runeLength(result) <= 30)
        // 结果必须是若干个完整名字用「、」拼接，不能有半个名字
        result.split("、").forEach { part -> assert(names.contains(part) || part.isEmpty()) }
    }

    @Test
    fun `首名本身就超长时硬截首名`() {
        val longName = "a".repeat(50)
        val result = GroupNameDefault.defaultName(listOf(longName), maxLen = 30)
        assertEquals(30, GroupNameDefault.runeLength(result))
    }

    @Test
    fun `全部为空白名时返回空串`() {
        assertEquals("", GroupNameDefault.defaultName(listOf("", "  ", "\n")))
    }
}
