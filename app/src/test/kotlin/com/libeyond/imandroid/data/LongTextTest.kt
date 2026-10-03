package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 长文本三档（对齐 iOS / Web 的边界：299/300、9/10 行、1999/2000、59/60 行）。 */
class LongTextTest {
    private fun chars(n: Int) = "字".repeat(n)
    private fun lines(n: Int) = List(n) { "a" }.joinToString("\n")

    @Test fun `字数边界`() {
        assertEquals(TextTier.Short, LongText.tierOf(chars(299)))
        assertEquals(TextTier.Long, LongText.tierOf(chars(300)))
        assertEquals(TextTier.Long, LongText.tierOf(chars(1999)))
        assertEquals(TextTier.Huge, LongText.tierOf(chars(2000)))
    }

    @Test fun `行数边界`() {
        assertEquals(TextTier.Short, LongText.tierOf(lines(9)))
        assertEquals(TextTier.Long, LongText.tierOf(lines(10)))
        assertEquals(TextTier.Long, LongText.tierOf(lines(59)))
        assertEquals(TextTier.Huge, LongText.tierOf(lines(60)))
    }

    @Test fun `字数按码点算，emoji 只算一个`() {
        val emoji = "😀".repeat(299) // UTF-16 长度 598，码点 299
        assertEquals(299, LongText.charCount(emoji))
        assertEquals(TextTier.Short, LongText.tierOf(emoji))
        assertEquals(TextTier.Long, LongText.tierOf("😀".repeat(300)))
    }

    @Test fun `行数不 trim，末尾换行也算一行`() {
        assertEquals(10, LongText.lineCount("a\n".repeat(9)))
        assertEquals(TextTier.Long, LongText.tierOf("a\n".repeat(9)))
    }

    @Test fun `整条就是链接恒 Short，混在文字里的不豁免`() {
        val url = "https://example.com/" + "a".repeat(400)
        assertEquals(TextTier.Short, LongText.tierOf(url))
        assertEquals(TextTier.Long, LongText.tierOf("看这个 $url"))
    }

    @Test fun `空串是 Short`() = assertEquals(TextTier.Short, LongText.tierOf(""))

    @Test fun `折叠先按 8 行再按 400 码点截`() {
        assertEquals(lines(8), LongText.collapsed(lines(20)))
        assertEquals(chars(400), LongText.collapsed(chars(900)))
    }

    @Test fun `截断不劈开代理对`() {
        val s = "😀".repeat(500)
        val t = LongText.collapsed(s)
        assertEquals(400, LongText.charCount(t))
        assertEquals(t, String(t.toCharArray())) // 无孤立代理项
    }

    @Test fun `摘要卡预览取 3 行 120 字且换行变空格`() {
        assertEquals("a a a", LongText.hugePreview(lines(10)))
        assertEquals(chars(120), LongText.hugePreview(chars(500)))
    }

    @Test fun `字数标签带千分位`() = assertEquals("8,400", LongText.countLabel(8400))
}
