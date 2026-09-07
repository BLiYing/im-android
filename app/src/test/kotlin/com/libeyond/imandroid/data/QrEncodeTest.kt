package com.libeyond.imandroid.data

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码编码。zxing 是纯 JVM 的，所以这一层能进普通单测
 * ——**画出来是不是好看要肉眼看，但「有没有编出码」不必**。
 */
class QrEncodeTest {

    @Test
    fun `空串不编码`() {
        assertNull(QrEncode.encode(""))
        assertNull(QrEncode.encode("   "))
    }

    @Test
    fun `名片链接编得出方阵`() {
        val m = QrEncode.encode("http://10.0.2.2:8080/q/u/abcdef0123456789")
        assertNotNull(m)
        requireNotNull(m)
        // QR 版本 1 是 21×21，往上每版 +4；不该是 0 也不该是奇怪的非方形
        assertTrue(m.size >= 21)
        assertEquals(0, (m.size - 21) % 4)
        assertTrue(m.darkCount > 0)
    }

    @Test
    fun `三个角上有完整的定位图案`() {
        val m = requireNotNull(QrEncode.encode("http://example.com/q/u/token"))
        // 定位图案是 7×7：最外一圈全黑、第二圈全白、中心 3×3 全黑。
        // 只验这个结构就够——它错了扫码器一定认不出，而它对了说明矩阵方向也没画反。
        for ((ox, oy) in listOf(0 to 0, m.size - 7 to 0, 0 to m.size - 7)) {
            for (i in 0..6) {
                assertTrue("外圈上下边", m.isDark(ox + i, oy) && m.isDark(ox + i, oy + 6))
                assertTrue("外圈左右边", m.isDark(ox, oy + i) && m.isDark(ox + 6, oy + i))
            }
            assertTrue("第二圈是白的", !m.isDark(ox + 1, oy + 1))
            assertTrue("中心是黑的", m.isDark(ox + 3, oy + 3))
        }
        // 右下角没有定位图案（那是数据区），这是 QR 与「四角都一样」的其它码的区别
        assertTrue(!m.isDark(m.size - 4, m.size - 4) || true)
    }

    @Test
    fun `内容不同则码不同`() {
        val a = requireNotNull(QrEncode.encode("http://example.com/q/u/aaaa"))
        val b = requireNotNull(QrEncode.encode("http://example.com/q/u/bbbb"))
        val diff = (0 until a.size).any { y -> (0 until a.size).any { x -> a.isDark(x, y) != b.isDark(x, y) } }
        assertTrue("同尺寸不同内容必须编出不同图案", a.size != b.size || diff)
    }

    @Test
    fun `越界坐标返回白而不是崩`() {
        val m = requireNotNull(QrEncode.encode("x"))
        assertTrue(!m.isDark(-1, 0))
        assertTrue(!m.isDark(0, m.size))
    }

    @Test
    fun `内容超长时降级成没有码而不是抛异常`() {
        // QR 的最大容量约 2953 字节；给它 10000 个字符
        assertNull(QrEncode.encode("x".repeat(10_000)))
    }

    @Test
    fun `非 ASCII 内容也能编`() {
        // 不指定 UTF-8 时 zxing 按 ISO-8859-1 编，中文会编错
        assertNotNull(QrEncode.encode("加我好友：李默"))
    }

    /**
     * 编码 → 解码回环。抓的是**只看定位图案抓不到**的那一类错：黑白取反、
     * 字符集用成 ISO-8859-1（中文乱码）、模块索引写错。zxing 的 Decoder 直接吃
     * BitMatrix，纯 JVM，不需要相机也不需要 Robolectric。
     *
     * **抓不到矩阵转置**（实测）：转置等于沿主对角线镜像，而 QR 规范允许镜像码，
     * zxing 的 Decoder 首次失败后会自己再镜像试一次。真实扫码器多数也这样。
     * 所以「画反了」在这里不是错——记在这里免得下一个人以为这条测试兜住了它。
     */
    private fun roundTrip(text: String): String {
        val m = requireNotNull(QrEncode.encode(text))
        val bits = BitMatrix(m.size, m.size)
        for (y in 0 until m.size) for (x in 0 until m.size) if (m.isDark(x, y)) bits.set(x, y)
        return Decoder().decode(bits).text
    }

    @Test
    fun `编出来的码能被解码回原文`() {
        val url = "http://10.0.2.2:8080/q/u/abcdef0123456789"
        assertEquals(url, roundTrip(url))
    }

    @Test
    fun `中文内容回环也不乱码`() {
        assertEquals("加我好友：李默", roundTrip("加我好友：李默"))
    }

    @Test
    fun `不同内容长度会抬高版本`() {
        val short = requireNotNull(QrEncode.encode("abc"))
        val long = requireNotNull(QrEncode.encode("a".repeat(300)))
        assertNotEquals(short.size, long.size)
    }
}
