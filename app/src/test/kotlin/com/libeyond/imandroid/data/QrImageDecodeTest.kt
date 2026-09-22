package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一图多码识别。像 `QrEncodeTest` 一样自己把 [QrEncode] 编出的模块矩阵**手动栅格化**成
 * ARGB 像素数组——不需要 `android.graphics.Bitmap`/Robolectric，纯 JVM 走完整个图像识别管线
 * （比 `QrEncodeTest` 的 `Decoder` 回环更进一层：这里连定位图案检测都真的跑）。
 */
class QrImageDecodeTest {

    /** 把一枚 [QrMatrix] 栅格化成 (宽, 高, ARGB 像素) 三元组，四周留白边（quiet zone）——没有它扫不出来。 */
    private fun render(text: String, scale: Int = 4, quietModules: Int = 4): Triple<Int, Int, IntArray> {
        val m = requireNotNull(QrEncode.encode(text))
        val quietPx = quietModules * scale
        val side = m.size * scale + quietPx * 2
        val pixels = IntArray(side * side) { WHITE }
        for (y in 0 until m.size) {
            for (x in 0 until m.size) {
                if (!m.isDark(x, y)) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val px = quietPx + x * scale + dx
                        val py = quietPx + y * scale + dy
                        pixels[py * side + px] = BLACK
                    }
                }
            }
        }
        return Triple(side, side, pixels)
    }

    /** 把两张已栅格化的图水平并排贴进一张更宽的白底画布，中间留足够的空白隔开——两个独立的码。 */
    private fun sideBySide(a: Triple<Int, Int, IntArray>, b: Triple<Int, Int, IntArray>, gap: Int = 40): Triple<Int, Int, IntArray> {
        val (aw, ah, apx) = a
        val (bw, bh, bpx) = b
        val h = maxOf(ah, bh)
        val w = aw + gap + bw
        val pixels = IntArray(w * h) { WHITE }
        for (y in 0 until ah) for (x in 0 until aw) pixels[y * w + x] = apx[y * aw + x]
        for (y in 0 until bh) for (x in 0 until bw) pixels[y * w + (aw + gap + x)] = bpx[y * bw + x]
        return Triple(w, h, pixels)
    }

    @Test
    fun `空白图片识别不到码，回空列表不崩`() {
        val side = 200
        val blank = IntArray(side * side) { WHITE }
        assertEquals(emptyList<String>(), QrImageDecode.decode(side, side, blank))
    }

    @Test
    fun `尺寸与像素数组对不上时直接回空列表`() {
        assertEquals(emptyList<String>(), QrImageDecode.decode(10, 10, IntArray(5)))
        assertEquals(emptyList<String>(), QrImageDecode.decode(0, 10, IntArray(0)))
    }

    @Test
    fun `单枚码原样识别出来`() {
        val url = "http://10.0.2.2:8080/q/g/abcdef0123456789"
        val (w, h, px) = render(url)
        assertEquals(listOf(url), QrImageDecode.decode(w, h, px))
    }

    @Test
    fun `一张图两枚码都能识别到——群公告截图常同时有群码与客服码`() {
        val a = "http://10.0.2.2:8080/q/g/aaaa1111"
        val b = "http://10.0.2.2:8080/q/u/bbbb2222"
        val (w, h, px) = sideBySide(render(a), render(b))
        val got = QrImageDecode.decode(w, h, px)
        assertEquals(2, got.size)
        assertTrue(got.containsAll(listOf(a, b)))
    }

    @Test
    fun `候选按面积从大到小排——大码（通常是用户想扫的主码）排第一，不管它在图里排第几个`() {
        val big = "http://10.0.2.2:8080/q/g/bigbig1111"
        val small = "http://10.0.2.2:8080/q/u/small2222"
        // 小码放左边、大码放右边——若结果不是按面积排而是按扫描顺序/左右位置排，这个方向会翻车，
        // 正好把「巧合按位置排对」和「真的按面积排」两种可能分开。
        val (w, h, px) = sideBySide(render(small, scale = 3), render(big, scale = 8))
        assertEquals(listOf(big, small), QrImageDecode.decode(w, h, px))
    }

    companion object {
        private const val WHITE = -0x1 // 0xFFFFFFFF.toInt()
        private const val BLACK = -0x1000000 // 0xFF000000.toInt()
    }
}
