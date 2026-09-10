package com.libeyond.imandroid.data

import kotlin.math.floor

/**
 * 聊天页图片/视频气泡的显示尺寸，**逐行照搬 iOS `IMMediaDisplaySize`**（`IMMediaFormat.m`）
 * 与 `IMImageCell.maxBox`。
 *
 * 本端此前是「宽 240 撑满 + 按比例定高、比例夹在 0.5..2」：竖图被裁掉上下，
 * 小图被放大糊掉，且宽恒为 240——配上图说时，图说那一行 `fillMaxWidth` 把气泡撑得比图宽，
 * 图就离气泡左边空出一截（十七条对齐 #16）。
 */
object MediaDisplaySize {

    const val MAX_WIDTH = 240f
    const val MAX_HEIGHT = 320f

    /** 极端长条的短边下限（保证点得到）。iOS `kIMMediaMinSide`。 */
    const val MIN_SIDE = 80f

    /** 服务端没给宽高时的方块边长。iOS `kIMMediaFallbackSide`。 */
    const val FALLBACK_SIDE = 180f

    /** 最大宽占屏宽的比例（窄屏上 240 太宽）。 */
    const val SCREEN_FRACTION = 0.62f

    data class Size(val width: Float, val height: Float)

    /** 可用框：宽 = min(240, 屏宽 × 0.62)，高 320。 */
    fun box(screenWidth: Float): Size = Size(minOf(MAX_WIDTH, screenWidth * SCREEN_FRACTION), MAX_HEIGHT)

    /**
     * 按原图像素把媒体装进 [box]：只缩不放（1dp/px 封顶），短边不足 [minSide] 再等比放大并逐维夹回框内。
     * 像素未知 → 方块；框无效 → 0×0。
     */
    fun fit(pixelW: Int?, pixelH: Int?, box: Size, minSide: Float = MIN_SIDE): Size {
        if (box.width <= 0f || box.height <= 0f) return Size(0f, 0f)
        val pw = (pixelW ?: 0).toDouble()
        val ph = (pixelH ?: 0).toDouble()
        if (pw <= 0 || ph <= 0) {
            val side = minOf(box.width, box.height, FALLBACK_SIDE)
            return Size(side, side)
        }
        val k = minOf(box.width / pw, box.height / ph, 1.0)
        var w = roundHalfUp(pw * k)
        var h = roundHalfUp(ph * k)
        val short = minOf(w, h)
        if (minSide > 0 && short > 0 && short < minSide) {
            val up = minSide / short
            w = minOf(roundHalfUp(w * up), box.width.toDouble())
            h = minOf(roundHalfUp(h * up), box.height.toDouble())
        }
        return Size(w.toFloat(), h.toFloat())
    }

    /** C 的 `round`（远离零取整）。`kotlin.math.round` 是银行家舍入，x.5 时与 iOS 差 1。 */
    private fun roundHalfUp(x: Double): Double = floor(x + 0.5)
}
