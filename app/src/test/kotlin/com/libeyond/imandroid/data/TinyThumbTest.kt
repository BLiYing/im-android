package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极小缩略 `thumb` 的三端契约。每条都对应 iOS `IMTinyThumbDataURI` /
 * Web `tinyThumbDataURL` 里一句写了理由的注释——**三端不一致时没有任何自动手段能发现**，
 * 表现只是"某一端的占位比另一端糊/清楚"。
 */
class TinyThumbTest {

    @Test
    fun `等比缩进 20 见方`() {
        assertEquals(20 to 15, TinyThumb.targetSize(400, 300))
        assertEquals(15 to 20, TinyThumb.targetSize(300, 400))
        assertEquals(20 to 20, TinyThumb.targetSize(1000, 1000))
    }

    @Test
    fun `不放大小图`() {
        // k 封顶 1：一张 8x6 的图缩略仍是 8x6，放大只会得到一团更大的马赛克
        assertEquals(8 to 6, TinyThumb.targetSize(8, 6))
    }

    @Test
    fun `极端比例也至少留 1 像素`() {
        // 2000x10 直接按比例算高会是 0，0 像素的位图解不出来
        val (w, h) = TinyThumb.targetSize(2000, 10)
        assertTrue(w >= 1 && h >= 1)
    }

    @Test
    fun `尺寸未知返回 0x0，调用方据此不生成`() {
        assertEquals(0 to 0, TinyThumb.targetSize(0, 100))
        assertEquals(0 to 0, TinyThumb.targetSize(-1, -1))
    }

    @Test
    fun `代理图会放大——它是拿来模糊的，不是拿来看的`() {
        // targetSize 封顶 1 不放大；proxySize 相反，20px 要先放到 48 再模糊
        assertEquals(48 to 36, TinyThumb.proxySize(20, 15))
    }

    @Test
    fun `超长的 thumb 宁可不带`() {
        // 服务端上限 4096 rune 且**会截断**——截断的 base64 解不出图，
        // 收端拿到一团噪声，比没有占位更糟
        val big = ByteArray(4000) { 0x41 }
        assertNull(TinyThumb.dataUri(big))
        val small = ByteArray(100) { 0x41 }
        assertNotNull(TinyThumb.dataUri(small))
    }

    @Test
    fun `空字节不生成`() {
        assertNull(TinyThumb.dataUri(null))
        assertNull(TinyThumb.dataUri(ByteArray(0)))
    }

    @Test
    fun `data URI 往返`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val uri = TinyThumb.dataUri(bytes)!!
        assertTrue(uri.startsWith(TinyThumb.PREFIX))
        val back = java.util.Base64.getDecoder().decode(TinyThumb.payloadOf(uri))
        assertTrue(bytes.contentEquals(back))
    }

    @Test
    fun `不是 JPEG data URI 的一律拒绝`() {
        assertNull(TinyThumb.payloadOf(null))
        assertNull(TinyThumb.payloadOf(""))
        assertNull(TinyThumb.payloadOf("https://x.com/a.jpg"))
        assertNull(TinyThumb.payloadOf("data:image/png;base64,AAAA"))
        assertNull(TinyThumb.payloadOf("data:image/jpeg;base64"))   // 没有逗号
        assertNull(TinyThumb.payloadOf("data:image/jpeg;base64,"))  // 逗号后是空的
    }

    @Test
    fun `模糊后边缘不发暗`() {
        // 一张纯白图模糊完还应该是纯白。**会坏的写法是把越界当成透明黑再除以整窗**——
        // 那样四周会往 0 靠，出来一圈灰边（实测这条变异确实红）。
        // iOS 用 clampToExtent、Web 用 scale(1.15) 把发暗的边挤出可视区，要的都是这一条。
        val w = 8; val h = 8
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val out = TinyThumb.boxBlur(px, w, h, 2)
        assertTrue(out.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `模糊确实把硬边抹掉了`() {
        // 左半黑右半白：模糊后交界处必须出现中间灰，否则等于没模糊
        val w = 8; val h = 4
        val px = IntArray(w * h) { i -> if (i % w < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val out = TinyThumb.boxBlur(px, w, h, 2)
        val mid = out[3] and 0xFF
        assertTrue("交界处应是中间灰，实得 $mid", mid in 1..254)
    }

    @Test
    fun `模糊不改尺寸也不崩在退化输入上`() {
        val px = IntArray(4) { 0xFF102030.toInt() }
        assertEquals(4, TinyThumb.boxBlur(px, 2, 2, 1).size)
        // 半径 0 / 尺寸非法：原样返回，不抛
        assertEquals(px.size, TinyThumb.boxBlur(px, 2, 2, 0).size)
        assertEquals(px.size, TinyThumb.boxBlur(px, 0, 0, 2).size)
    }
}
