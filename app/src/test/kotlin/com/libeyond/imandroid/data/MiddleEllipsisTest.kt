package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文件名中间截断（CHAT_UI_SKETCH §5，十七条对齐 #13）。 */
class MiddleEllipsisTest {

    private fun upTo(n: Int): (String) -> Boolean = { graphemeClusters(it).size <= n }

    @Test
    fun `放得下原样返回`() {
        assertEquals("报表.xlsx", MiddleEllipsis.fit("报表.xlsx", upTo(20)))
    }

    @Test
    fun `放不下从中间截，保住扩展名`() {
        // 放 10 个：留 9 个字 + 「…」，前 5 后 4
        assertEquals("abcde….pdf", MiddleEllipsis.fit("abcdefghijklmnop.pdf", upTo(10)))
    }

    @Test
    fun `一个字都放不下只剩省略号`() {
        assertEquals("…", MiddleEllipsis.fit("abcdef", upTo(1)))
    }

    @Test
    fun `不会把 emoji 劈成半个`() {
        val out = MiddleEllipsis.fit("😀".repeat(10) + ".txt", upTo(8))
        assertTrue("出现了孤立代理项：$out", hasOnlyPairs(out))
        assertEquals(8, graphemeClusters(out).size)
    }

    private fun hasOnlyPairs(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
            } else if (Character.isLowSurrogate(ch)) {
                return false
            } else {
                i++
            }
        }
        return true
    }
}
