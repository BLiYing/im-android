package com.libeyond.imandroid.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IMTextPrompt] 的字数判据：变短永远放行；超限且改过不让确认；没改照常可确认。 */
class TextPromptRulesTest {

    private val long40 = "字".repeat(40)

    @Test
    fun `超长旧值可以往短里删`() {
        assertTrue(TextPromptRules.acceptsEdit(long40.dropLast(1), long40, 10, countByCodePoint = true))
        assertFalse("没超限的值不能再变长到超限", TextPromptRules.acceptsEdit("a".repeat(11), "a".repeat(10), 10, false))
    }

    @Test
    fun `全选粘贴一段更短但仍超限的文字：输入放行，确认禁用`() {
        val pasted = "字".repeat(20)
        assertTrue(TextPromptRules.acceptsEdit(pasted, long40, 10, countByCodePoint = true))
        assertFalse(TextPromptRules.canConfirm(pasted, long40, 10, countByCodePoint = true))
    }

    @Test
    fun `没改（等于初始值）即使超限也可确认；不超限照常可确认`() {
        assertTrue(TextPromptRules.canConfirm(long40, long40, 10, countByCodePoint = true))
        assertTrue(TextPromptRules.canConfirm("字".repeat(10), long40, 10, countByCodePoint = true))
        assertTrue(TextPromptRules.canConfirm("", "", 10, countByCodePoint = false))
    }

    @Test
    fun `按码点计：emoji 算 1`() {
        val emoji = "😀".repeat(10) // UTF-16 长 20
        assertTrue(TextPromptRules.canConfirm(emoji, "", 10, countByCodePoint = true))
        assertFalse(TextPromptRules.canConfirm(emoji, "", 10, countByCodePoint = false))
    }
}
