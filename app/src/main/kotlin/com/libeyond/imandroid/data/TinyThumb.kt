package com.libeyond.imandroid.data

/**
 * 极小模糊缩略 `thumb`（M4-7，PROTOCOL §4.3）——**随每条图片/视频消息常驻下发的
 * ~20px 低质 JPEG data URI**，收端在原图到位之前放大 + 模糊当占位
 * （Telegram 的 stripped thumbnail，iOS `IMTinyThumbDataURI` / Web `tinyThumbDataURL`）。
 *
 * **它刻意做得极小（<1KB）**：随每条媒体消息走，大了会拖慢会话同步。
 * 服务端上限 4096 rune 且会截断——被截断的 base64 解不出图，收端只会拿到一团噪声，
 * 所以宁可**不带**也不能带一个超长的（三端都在发送侧就把关）。
 *
 * 这里只放**纯逻辑**（尺寸换算 / data URI 拆装 / 模糊核），
 * 编解码在 [com.libeyond.imandroid.ui.FrostedThumb]（要 android.graphics）。
 */
object TinyThumb {

    /** 缩略图最长边（三端同为 20：iOS `maxSide` / Web `tinyThumbTargetSize` 默认值）。 */
    const val MAX_SIDE = 20

    /** JPEG 质量（三端同为 0.4）。 */
    const val QUALITY = 40

    const val PREFIX = "data:image/jpeg;base64,"

    /**
     * data URI 的长度上限。
     *
     * 服务端 `thumb` 上限 4096 rune；这里取 3800 与 Web 逐字一致
     * （iOS 用等价的另一头：原始 JPEG > 2800 B 就放弃，base64 膨胀 4/3 后约 3756）。
     */
    const val MAX_DATA_URI_LEN = 3800

    /** 磨砂代理图的最长边（iOS `kIMFrostedProxyMaxSide`）：先放大到它再模糊，成本极低。 */
    const val PROXY_MAX_SIDE = 48

    /** 盒式模糊半径，三趟≈高斯 sigma 4（iOS `kIMFrostedBlurSigma`）。 */
    const val BLUR_RADIUS = 4

    /**
     * 缩略图目标像素尺寸：等比缩进 [maxSide] 见方，**不放大小图**（k 封顶 1）。
     * 尺寸未知（≤0）返回 0×0，调用方据此不生成 thumb。
     */
    fun targetSize(w: Int, h: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val k = minOf(maxSide.toDouble() / w, maxSide.toDouble() / h, 1.0)
        return maxOf(1, Math.round(w * k).toInt()) to maxOf(1, Math.round(h * k).toInt())
    }

    /** 磨砂代理图尺寸：把 ~20px 的缩略**放大**到 48 见方再模糊（这里允许放大）。 */
    fun proxySize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val k = if (w >= h) PROXY_MAX_SIDE.toDouble() / w else PROXY_MAX_SIDE.toDouble() / h
        return maxOf(1, Math.round(w * k).toInt()) to maxOf(1, Math.round(h * k).toInt())
    }

    /** JPEG 字节 → data URI；空或超限返回 null（**宁可不带**，见类注释）。 */
    fun dataUri(jpeg: ByteArray?): String? {
        if (jpeg == null || jpeg.isEmpty()) return null
        val uri = PREFIX + java.util.Base64.getEncoder().encodeToString(jpeg)
        return uri.takeIf { it.length <= MAX_DATA_URI_LEN }
    }

    /** data URI → base64 载荷；不是 JPEG data URI / 没有逗号 → null。 */
    fun payloadOf(uri: String?): String? {
        if (uri.isNullOrEmpty() || !uri.startsWith("data:image/jpeg")) return null
        val comma = uri.indexOf(',')
        if (comma < 0 || comma == uri.length - 1) return null
        return uri.substring(comma + 1)
    }

    /**
     * 三趟盒式模糊（≈高斯）。就地算在 ARGB 像素数组上，**不碰 android.graphics**，可单测。
     *
     * **边缘钳制**（取样超出边界时用最边上那一列/行）——不钳的话模糊完四周会发暗，
     * iOS 用 `imageByClampingToExtent` 解决同一件事，Web 用 `transform: scale(1.15)` 把
     * 发暗的边挤出可视区。三端手段不同，要的都是「模糊后边缘不发暗」。
     */
    fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int = BLUR_RADIUS): IntArray {
        if (w <= 0 || h <= 0 || radius <= 0 || pixels.size < w * h) return pixels
        var src = pixels.copyOf()
        var dst = IntArray(pixels.size)
        repeat(3) {
            blurPass(src, dst, w, h, radius, horizontal = true)
            blurPass(dst, src, w, h, radius, horizontal = false)
        }
        dst = src
        return dst
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
                for (d in -r..r) {
                    // 钳到边界：超出的取最边上那一格，避免四周发暗
                    val j = (i + d).coerceIn(0, inner - 1)
                    val p = if (horizontal) src[o * w + j] else src[j * w + o]
                    a += (p ushr 24) and 0xFF
                    rr += (p ushr 16) and 0xFF
                    gg += (p ushr 8) and 0xFF
                    bb += p and 0xFF
                    n++
                }
                val v = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
                if (horizontal) dst[o * w + i] = v else dst[i * w + o] = v
            }
        }
    }
}
