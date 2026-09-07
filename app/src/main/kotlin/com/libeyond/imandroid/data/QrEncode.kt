package com.libeyond.imandroid.data

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * 一张二维码的**模块矩阵**（黑白格子），不含静默区。
 *
 * 刻意不是位图：位图要绑定像素尺寸与屏幕密度，而这里只描述「几乘几的格子，哪些是黑的」，
 * 由渲染方（Compose Canvas / 保存到相册的 Bitmap）各自按自己的尺寸画。
 * 好处是编码这一步是纯 JVM 的，能进普通单测。
 */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    init {
        require(size > 0 && dark.size == size * size) { "矩阵尺寸与数据长度不符" }
    }

    fun isDark(x: Int, y: Int): Boolean =
        x in 0 until size && y in 0 until size && dark[y * size + x]

    /** 黑格数量。测试用来判定「不同内容编出不同码」而不必逐格比对。 */
    val darkCount: Int get() = dark.count { it }
}

/**
 * 二维码编码（「我」页名片码）。
 *
 * 纠错级别 **M**，与 iOS `IMQRImage` 的 `inputCorrectionLevel = @"M"` 一致——
 * 三端扫同一枚 token 编出的码要能互扫，级别不一致虽然仍可扫，但模块密度不同、
 * 视觉与「同一张码」的预期对不上。
 */
object QrEncode {

    /**
     * @return 编码结果；[text] 为空或 zxing 编不出（内容过长等）时返回 null。
     *
     * **不抛异常**：调用方是 UI，拿到 null 就显占位。为一段展示用的图形把页面炸掉不值当。
     */
    fun encode(text: String): QrMatrix? {
        if (text.isBlank()) return null
        return try {
            val hints = mapOf(
                // 不指定字符集时 zxing 按 ISO-8859-1 编，URL 里若出现非 ASCII 会编错。
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val code = Encoder.encode(text, ErrorCorrectionLevel.M, hints)
            val m = code.matrix ?: return null
            // ByteMatrix 是**不含静默区**的裸模块阵；留白由渲染方给（卡片本身就是白底）。
            val w = m.width
            val h = m.height
            if (w <= 0 || w != h) return null
            val dark = BooleanArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    dark[y * w + x] = m.get(x, y).toInt() == 1
                }
            }
            QrMatrix(w, dark)
        } catch (e: Exception) {
            // zxing 对超长内容抛 WriterException；这里连同任何编码异常一起降级成「没有码」。
            null
        }
    }
}
