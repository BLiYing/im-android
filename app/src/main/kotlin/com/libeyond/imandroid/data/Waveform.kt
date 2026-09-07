package com.libeyond.imandroid.data

import android.util.Base64

/**
 * 语音波形（PROTOCOL §4.3 `waveform`）。
 *
 * 契约与 iOS `IMWaveformView` / im-web `amplitudesFromBase64` 逐条一致：
 * - `waveform` 是 **base64**，解出来每个字节是 **0~100 的振幅**（不是 0~255）；
 * - 空 / 非法 → **退化成等高条纹**，这是**协议允许的合法状态**不是错误
 *   （老消息、以及录制端没采到振幅时本就没有这个字段）；
 * - 条数由 UI 决定，采样按桶**取最大值**不是平均——取平均会把波形抹平成一条直线，
 *   而波形的全部意义就是让人一眼看出"哪里在说话"。
 *
 * 抽成纯函数是因为这里有三处容易各写各的：归一化上限、桶边界、退化值。
 * 三端不一致时同一条语音在三端波形不同，而这没有任何自动化手段能发现。
 */
object Waveform {

    /** 无波形时的等高条纹高度（0~1）。与 im-web `out[i] = 0.35` 同值。 */
    const val FLAT = 0.35f

    /** 振幅上限：服务端与三端录制都按 0~100 记。 */
    private const val AMP_MAX = 100

    /**
     * base64 → 0~1 归一化振幅数组。空 / 非法 / 解出来是空 → null（调用方退化等高条纹）。
     *
     * @param decoder 解 base64 的实现。默认走 Android 的 `Base64`；单测里注入纯 JVM 实现，
     *   免得为了测一段纯算法去拉 Robolectric。
     */
    fun amplitudes(b64: String?, decoder: (String) -> ByteArray? = ::androidDecode): FloatArray? {
        if (b64.isNullOrBlank()) return null
        val bytes = decoder(b64) ?: return null
        if (bytes.isEmpty()) return null
        return FloatArray(bytes.size) { i ->
            // 字节按**无符号**读：振幅 >127 时按有符号读会变成负数，波形直接塌掉
            val v = bytes[i].toInt() and 0xFF
            minOf(AMP_MAX, v).toFloat() / AMP_MAX
        }
    }

    /**
     * 把振幅数组下采样成 [count] 根柱子的高度（0~1）。
     * 振幅为 null / 空 → 全部 [FLAT]（等高条纹）。
     */
    fun bars(amps: FloatArray?, count: Int): FloatArray {
        require(count > 0) { "count 必须为正" }
        if (amps == null || amps.isEmpty()) return FloatArray(count) { FLAT }
        return FloatArray(count) { i ->
            val lo = (i.toLong() * amps.size / count).toInt()
            val hi = maxOf(lo + 1, ((i + 1).toLong() * amps.size / count).toInt())
            var v = 0f
            for (j in lo until minOf(amps.size, hi)) if (amps[j] > v) v = amps[j]
            v
        }
    }

    /** 一步到位：base64 → 柱子高度。 */
    fun barsOf(b64: String?, count: Int, decoder: (String) -> ByteArray? = ::androidDecode): FloatArray =
        bars(amplitudes(b64, decoder), count)

    private fun androidDecode(b64: String): ByteArray? =
        runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
}
