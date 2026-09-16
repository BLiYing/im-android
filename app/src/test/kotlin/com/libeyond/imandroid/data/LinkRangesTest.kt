package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 气泡正文里全部链接的区间（正则逐字照抄 iOS `IMURLRegexShared`）+ 按链接切段。 */
class LinkRangesTest {

    private fun urls(text: String) = LinkDetect.urlRanges(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `汉字与中文标点是天然边界——iOS 老正则把整句吸进去过`() {
        assertEquals(listOf("https://foo.com"), urls("分身乏术，https://foo.com，好文"))
        assertEquals(listOf("https://a.com/x"), urls("https://a.com/x是这个"))
    }

    @Test
    fun `句末标点与右括号不算地址`() {
        assertEquals(listOf("https://a.com/x"), urls("看这个 https://a.com/x."))
        assertEquals(listOf("https://a.com/x"), urls("(见 https://a.com/x)"))
        assertEquals(listOf("http://b.com/p?q=1"), urls("http://b.com/p?q=1?"))
    }

    @Test
    fun `一段里有几个就标几个`() {
        assertEquals(
            listOf("https://a.com", "http://b.com/p?q=1&r=2"),
            urls("https://a.com 和 http://b.com/p?q=1&r=2"),
        )
    }

    @Test
    fun `只认小写 http 与 https 开头——与 iOS 同样不标 www 与大写 scheme`() {
        assertTrue(urls("www.a.com").isEmpty())
        assertTrue(urls("HTTPS://A.COM").isEmpty())
        assertTrue(urls("https://").isEmpty())
    }

    @Test
    fun `链接在段中间切成三段`() {
        assertEquals(
            listOf(LinkDetect.Piece(0, 2, null), LinkDetect.Piece(2, 6, 2..5), LinkDetect.Piece(6, 9, null)),
            LinkDetect.splitByLinks(0, 9, listOf(2..5)),
        )
    }

    @Test
    fun `链接跨过段尾时截在段尾但记着整条地址——点截断的那半也要打开整条`() {
        assertEquals(
            listOf(LinkDetect.Piece(0, 5, null), LinkDetect.Piece(5, 10, 5..14)),
            LinkDetect.splitByLinks(0, 10, listOf(5..14)),
        )
    }

    @Test
    fun `没有链接或链接落在段外时整段原样`() {
        assertEquals(listOf(LinkDetect.Piece(3, 8, null)), LinkDetect.splitByLinks(3, 8, emptyList()))
        assertEquals(listOf(LinkDetect.Piece(3, 8, null)), LinkDetect.splitByLinks(3, 8, listOf(10..20)))
        assertEquals(listOf(LinkDetect.Piece(3, 8, null)), LinkDetect.splitByLinks(3, 8, listOf(0..2)))
    }
}
