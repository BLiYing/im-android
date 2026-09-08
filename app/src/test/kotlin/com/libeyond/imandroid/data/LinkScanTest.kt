package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「链接」页签的判据。服务端不覆盖这一格（链接不是独立 content_type），
 * 只能像 iOS 一样扫本地文本——所以这段逻辑必须自己钉住。
 */
class LinkScanTest {

    @Test
    fun `整段就是一个链接`() {
        assertEquals("https://example.com", LinkScan.firstUrl("https://example.com"))
    }

    /**
     * **混排文本也要算**。iOS 早期只认「整段 = URL」，漏掉了「看看 https://x 这个」这类，
     * 后来改成了本口径（IMChatDetailTabs 的注释里记着）。
     */
    @Test
    fun `混排文本里的链接也要认出来`() {
        assertEquals("https://x.com/a", LinkScan.firstUrl("看看 https://x.com/a 这个"))
        assertTrue(LinkScan.hasUrl("前面一堆字 http://a.cn 后面一堆字"))
    }

    /** 结尾标点不算 URL 的一部分——不剥的话「(见 https://x.com)」会带上右括号。 */
    @Test
    fun `结尾标点要剥掉`() {
        assertEquals("https://x.com", LinkScan.firstUrl("(见 https://x.com)"))
        assertEquals("https://x.com", LinkScan.firstUrl("去 https://x.com。"))
        assertEquals("https://x.com", LinkScan.firstUrl("去 https://x.com，然后"))
    }

    /**
     * **中文不打空格**：按"截到下一个空格"会把「，然后」一起吞进 URL。
     * 第一版就是这么写的，被这条测试当场抓住。改成按 URL 合法字符集截断。
     */
    @Test
    fun `中文紧跟在链接后面也要断开`() {
        assertEquals("https://x.com", LinkScan.firstUrl("去 https://x.com看看"))
        assertEquals("https://x.com/a", LinkScan.firstUrl("https://x.com/a这个链接"))
    }

    @Test
    fun `取第一个而不是最后一个`() {
        assertEquals("http://a.cn", LinkScan.firstUrl("http://a.cn 和 https://b.cn"))
    }

    @Test
    fun `没有链接就是 null`() {
        assertNull(LinkScan.firstUrl("今天天气不错"))
        assertNull(LinkScan.firstUrl(""))
    }

    /** 光一个协议头不算链接——否则用户打「https://」也会进归档。 */
    @Test
    fun `裸协议头不算链接`() {
        assertNull(LinkScan.firstUrl("https://"))
        assertNull(LinkScan.firstUrl("看 http:// 就这样"))
    }

    /** 归档是"索引"视角：未确认的消息（convSeq<=0）不该进，与 iOS `matchesKind:` 同判。 */
    @Test
    fun `未确认的消息不进归档`() {
        assertFalse(LinkScan.isLinkMessage(ContentType.TEXT, "https://x.com", convSeq = 0))
        assertTrue(LinkScan.isLinkMessage(ContentType.TEXT, "https://x.com", convSeq = 5))
    }

    @Test
    fun `只有文本和 link 类型参与`() {
        assertFalse(LinkScan.isLinkMessage(ContentType.IMAGE, "https://x.com", convSeq = 5))
        assertTrue(LinkScan.isLinkMessage("link", "https://x.com", convSeq = 5))
    }

    /** 单聊没有「成员」页签，群聊有；顺序即 iOS 的页签顺序。 */
    @Test
    fun `页签集合按会话类型`() {
        assertEquals(
            listOf(DetailTab.Media, DetailTab.Files, DetailTab.Voice, DetailTab.Links),
            DetailTabs.visible(isGroup = false),
        )
        assertEquals(DetailTab.Members, DetailTabs.visible(isGroup = true).first())
    }

    /** 「链接」与「成员」不走归档接口——前者服务端没有可索引的列，后者不是消息。 */
    @Test
    fun `链接与成员不走归档接口`() {
        assertNull(DetailTabs.apiKind(DetailTab.Links))
        assertNull(DetailTabs.apiKind(DetailTab.Members))
        assertEquals("media", DetailTabs.apiKind(DetailTab.Media))
        assertEquals("file", DetailTabs.apiKind(DetailTab.Files))
        assertEquals("voice", DetailTabs.apiKind(DetailTab.Voice))
    }
}
