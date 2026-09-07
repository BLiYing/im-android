package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkDetectTest {

    @Test
    fun `认 http https 与 www 开头`() {
        assertEquals("https://a.com/x", LinkDetect.firstUrl("看这个 https://a.com/x 挺好"))
        assertEquals("http://a.com", LinkDetect.firstUrl("http://a.com"))
        // 无 scheme 的 www. 要补上 https，否则拿去请求必然失败
        assertEquals("https://www.a.com/x", LinkDetect.firstUrl("www.a.com/x"))
    }

    @Test
    fun `句末标点不算地址的一部分`() {
        // 带标点去请求会必然抓空，还白吃每账号 60 分钟的配额
        assertEquals("https://a.com", LinkDetect.firstUrl("看这个 https://a.com。"))
        assertEquals("https://a.com", LinkDetect.firstUrl("看这个 https://a.com，然后"))
        assertEquals("https://a.com", LinkDetect.firstUrl("https://a.com!"))
        // 中文里标点后面还有字：只剥末尾标点救不回来，字符类必须排除中日韩字符
        assertEquals("https://a.com", LinkDetect.firstUrl("看这个https://a.com，然后说点别的"))
        assertEquals("https://a.com/x", LinkDetect.firstUrl("https://a.com/x是这个"))
    }

    @Test
    fun `只取第一个——卡片只对第一个链接出`() {
        assertEquals("https://a.com", LinkDetect.firstUrl("https://a.com 和 https://b.com"))
    }

    @Test
    fun `裸域名不认——中文句子里误判率太高`() {
        assertNull(LinkDetect.firstUrl("等等.然后再说"))
        assertNull(LinkDetect.firstUrl("example.com"))
        assertNull(LinkDetect.firstUrl("没有链接"))
        assertNull(LinkDetect.firstUrl(""))
    }

    @Test
    fun `括号不吃进地址`() {
        assertEquals("https://a.com/x", LinkDetect.firstUrl("（见 https://a.com/x）"))
    }

    @Test
    fun `host 兜底去掉 www`() {
        assertEquals("a.com", LinkDetect.hostOf("https://www.a.com/x?y=1"))
        assertEquals("huarw.com", LinkDetect.hostOf("https://huarw.com/play/1"))
    }
}
