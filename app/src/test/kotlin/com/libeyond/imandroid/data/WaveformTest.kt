package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音波形解码与下采样。
 *
 * 注入纯 JVM 的 base64 解码器（`java.util.Base64`），**不为了测一段纯算法去拉 Robolectric**
 * ——`android.util.Base64` 在单测里是空壳（返回 0），拿它测等于什么都没测。
 */
class WaveformTest {

    private val jvm: (String) -> ByteArray? = { s ->
        runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()
    }

    private fun b64(vararg v: Int): String =
        java.util.Base64.getEncoder().encodeToString(v.map { it.toByte() }.toByteArray())

    @Test
    fun `振幅按 0 到 100 归一化，不是 0 到 255`() {
        val a = Waveform.amplitudes(b64(0, 50, 100), jvm)!!
        assertEquals(0f, a[0], 0.001f)
        assertEquals(0.5f, a[1], 0.001f)
        assertEquals(1f, a[2], 0.001f)
    }

    @Test
    fun `字节按无符号读——超过 127 的振幅不能变成负数`() {
        // 200 的字节在 Kotlin 里是 -56。按有符号读会得到负高度，波形直接塌掉。
        val a = Waveform.amplitudes(b64(200), jvm)!!
        assertTrue("振幅不得为负，实得 ${a[0]}", a[0] >= 0f)
        assertEquals("超上限一律钳到 1", 1f, a[0], 0.001f)
    }

    @Test
    fun `空 非法 空数组一律返回 null，由调用方退化等高条纹`() {
        assertNull(Waveform.amplitudes(null, jvm))
        assertNull(Waveform.amplitudes("", jvm))
        assertNull(Waveform.amplitudes("   ", jvm))
        assertNull(Waveform.amplitudes("!!!not-base64!!!", jvm))
        assertNull(Waveform.amplitudes(b64(), jvm))
    }

    @Test
    fun `无波形时全部退化成等高条纹`() {
        val bars = Waveform.bars(null, 10)
        assertEquals(10, bars.size)
        bars.forEach { assertEquals(Waveform.FLAT, it, 0.001f) }
    }

    @Test
    fun `下采样按桶取最大值——取平均会把波形抹平`() {
        // 4 个振幅压成 2 根柱：桶① max(0, 1)=1，桶② max(0, 0.5)=0.5。
        // 若实现取平均会得到 0.5 / 0.25，这条就红。
        val amps = floatArrayOf(0f, 1f, 0f, 0.5f)
        val bars = Waveform.bars(amps, 2)
        assertEquals(1f, bars[0], 0.001f)
        assertEquals(0.5f, bars[1], 0.001f)
    }

    @Test
    fun `柱数多于振幅数时不越界、不丢柱`() {
        val bars = Waveform.bars(floatArrayOf(0.2f, 0.8f), 8)
        assertEquals(8, bars.size)
        bars.forEach { assertTrue("柱高应落在 0..1，实得 $it", it in 0f..1f) }
        assertTrue("至少有一根柱拿到峰值", bars.any { it > 0.7f })
    }

    @Test
    fun `一步到位的 barsOf 与分步等价`() {
        val s = b64(10, 90, 40)
        val a = Waveform.barsOf(s, 3, jvm)
        val b = Waveform.bars(Waveform.amplitudes(s, jvm), 3)
        assertEquals(b.toList(), a.toList())
    }
}
