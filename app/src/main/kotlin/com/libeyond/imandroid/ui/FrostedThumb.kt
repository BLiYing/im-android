package com.libeyond.imandroid.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.libeyond.imandroid.data.TinyThumb

/**
 * 把消息里内嵌的极小缩略（[TinyThumb]）渲染成**磨砂占位**——原图到位之前显示它，
 * 而不是一块空底。对齐 iOS `IMMediaPlaceholder.frostedForThumb:` 与 Web 的 `.gate-blur`。
 *
 * **为什么不用 `Modifier.blur`**：那是 `RenderEffect`，**API 31 才有，31 以下静默无效**
 * （本仓 minSdk 26，手上的 Pixel 2 XL 是 API 30——真机上根本看不到效果，
 * 而编译与预览都不会报任何问题，属于"只在旧机上坏且没人发现"的那类）。
 * 所以照 iOS 的做法**在位图上算**：先把 ~20px 缩略放大到 48 见方的代理图，
 * 再跑三趟盒式模糊（≈高斯 sigma 4）。48×48 的三趟盒糊是微秒级，与 API 无关。
 *
 * 结果按 data URI 缓存：同一条消息在滚动中会被反复重组，不缓存就是每帧重算一次。
 */
object FrostedThumb {

    /** 每条媒体消息一份，48×48 ARGB ≈ 9KB；128 条约 1.2MB，够一屏滚动来回。 */
    private val cache = LruCache<String, ImageBitmap>(128)

    /** `null` = 没有 thumb / 解不出来（调用方留中性底，**不要为占位去联网**）。 */
    fun frosted(thumb: String?): ImageBitmap? {
        val payload = TinyThumb.payloadOf(thumb) ?: return null
        cache.get(thumb)?.let { return it }
        val out = runCatching { render(payload) }.getOrNull() ?: return null
        cache.put(thumb, out)
        return out
    }

    private fun render(base64: String): ImageBitmap? {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        if (bytes.isEmpty()) return null
        val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (small.width <= 0 || small.height <= 0) return null

        val (pw, ph) = TinyThumb.proxySize(small.width, small.height)
        if (pw <= 0 || ph <= 0) return null
        // filter=true：双线性放大本身就先柔化一层，模糊只需补掉 JPEG 块状
        val proxy = Bitmap.createScaledBitmap(small, pw, ph, true)

        val px = IntArray(pw * ph)
        proxy.getPixels(px, 0, pw, 0, 0, pw, ph)
        val blurred = TinyThumb.boxBlur(px, pw, ph, TinyThumb.BLUR_RADIUS)

        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        out.setPixels(blurred, 0, pw, 0, 0, pw, ph)
        return out.asImageBitmap()
    }
}

/**
 * 磨砂占位画笔，直接喂给 Coil 的 `placeholder=` / `error=`。
 *
 * 没有 thumb 就返回 null——**调用方要留中性底而不是白屏**：老消息（本端接 thumb
 * 之前发的）与三端历史消息都没有这个字段，回退路径是常态不是异常。
 */
@Composable
fun rememberFrostedPainter(thumb: String?): Painter? =
    remember(thumb) { FrostedThumb.frosted(thumb)?.let { BitmapPainter(it) } }
