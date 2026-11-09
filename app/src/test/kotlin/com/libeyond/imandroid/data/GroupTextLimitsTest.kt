package com.libeyond.imandroid.data

import com.libeyond.imandroid.ui.components.TextPromptRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 群名 / 群简介 / 群公告输入：一律按码点计；超长旧值可往短里删；改过且超限禁用确认。 */
class GroupTextLimitsTest {

    private val limits = ServerLimits(groupNameMaxLen = 10, groupAnnouncementMaxLen = 50)
    private val all = listOf(GroupTextLimits.name(limits), GroupTextLimits.intro(), GroupTextLimits.announcement(limits))

    @Test
    fun `上限取 server-config，简介是服务端常量 200`() {
        assertEquals(listOf(10, 200, 50), all.map { it.maxLen })
        assertEquals(GroupTextLimits.INTRO_MAX_LEN, GroupTextLimits.intro().maxLen)
    }

    @Test
    fun `三个字段都按码点计：满额 emoji 可输入可确认`() {
        for (l in all) {
            val full = "😀".repeat(l.maxLen) // UTF-16 长度是上限的两倍
            assertTrue("${l.maxLen}", TextPromptRules.acceptsEdit(full, full.dropLast(2), l.maxLen, l.countByCodePoint))
            assertTrue("${l.maxLen}", TextPromptRules.canConfirm(full, "", l.maxLen, l.countByCodePoint))
        }
    }

    @Test
    fun `超长旧值可往短里删；改过仍超限不让确认；没改可确认`() {
        for (l in all) {
            val old = "字".repeat(l.maxLen + 20)
            val shorter = old.dropLast(1)
            assertTrue(TextPromptRules.acceptsEdit(shorter, old, l.maxLen, l.countByCodePoint))
            assertFalse(TextPromptRules.canConfirm(shorter, old, l.maxLen, l.countByCodePoint))
            assertTrue(TextPromptRules.canConfirm(old, old, l.maxLen, l.countByCodePoint))
            assertTrue(TextPromptRules.canConfirm(old.take(l.maxLen), old, l.maxLen, l.countByCodePoint))
        }
    }
}
