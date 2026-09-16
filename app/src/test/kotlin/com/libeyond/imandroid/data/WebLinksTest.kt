package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.WebLinks.Nav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用内浏览器的入口地址与页内跳转判据。 */
class WebLinksTest {

    @Test
    fun `只在 App 内打开 http 与 https`() {
        assertEquals("https://a.com/x", WebLinks.entryUrl(" https://a.com/x "))
        assertEquals("HTTP://A.com", WebLinks.entryUrl("HTTP://A.com"))
        assertNull(WebLinks.entryUrl("ftp://a.com"))
        assertNull(WebLinks.entryUrl("javascript:alert(1)"))
        assertNull(WebLinks.entryUrl("https://"))
        assertNull(WebLinks.entryUrl(""))
    }

    @Test
    fun `www 开头补 https——预览卡认它，点卡片不能打不开`() {
        assertEquals("https://www.a.com", WebLinks.entryUrl("www.a.com"))
        assertNull(WebLinks.entryUrl("www."))
    }

    @Test
    fun `页内跳转 http 与 https 留在本页`() {
        assertEquals(Nav.InPage, WebLinks.navigationFor("https://a.com/next", userGesture = false))
        assertEquals(Nav.InPage, WebLinks.navigationFor("http://a.com", userGesture = true))
    }

    @Test
    fun `拉起应用的 scheme 只在用户真点了时交给系统——站点自动重定向到自家 App 要拦下`() {
        assertEquals(Nav.External, WebLinks.navigationFor("weixin://dl/business", userGesture = true))
        assertEquals(Nav.Block, WebLinks.navigationFor("weixin://dl/business", userGesture = false))
        assertEquals(Nav.External, WebLinks.navigationFor("intent://x#Intent;scheme=a;end", userGesture = true))
    }

    @Test
    fun `指向本机文件与网页自身内容的 scheme 点了也不交出去`() {
        assertEquals(Nav.Block, WebLinks.navigationFor("file:///sdcard/a.txt", userGesture = true))
        assertEquals(Nav.Block, WebLinks.navigationFor("content://com.x.provider/1", userGesture = true))
        assertEquals(Nav.Block, WebLinks.navigationFor("javascript:void(0)", userGesture = true))
        assertEquals(Nav.Block, WebLinks.navigationFor("no-scheme", userGesture = true))
    }

    @Test
    fun `未加密网页加载失败单独说明`() {
        assertTrue(WebLinks.isCleartext("http://a.com"))
        assertFalse(WebLinks.isCleartext("https://a.com"))
        assertTrue(WebLinks.failureText("http://a.com").contains("http"))
        assertFalse(WebLinks.failureText("https://a.com").contains("http"))
    }
}
