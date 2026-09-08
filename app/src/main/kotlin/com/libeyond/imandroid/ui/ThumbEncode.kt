package com.libeyond.imandroid.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.libeyond.imandroid.data.TinyThumb
import java.io.ByteArrayOutputStream

/**
 * 由**要发出去的那张图的字节**生成极小模糊缩略（[TinyThumb]）。
 *
 * 对齐 iOS `IMTinyThumbDataURI` / Web `tinyThumbDataURL`：最长边 20px、JPEG 质量 0.4、
 * 不放大小图、超长就不带。
 *
 * **失败一律返回 null，绝不阻断发送**——thumb 是锦上添花（收端回退中性底），
 * 为它把一条消息卡住是本末倒置。
 *
 * 视频传封面首帧的字节即可（iOS 也是拿封面生成的）。
 */
object ThumbEncode {

    fun fromImageBytes(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            // 先只读头拿尺寸，再按 inSampleSize 解一张够用的小图——
            // 原图可能是几千万像素，整张解进内存只为缩成 20px 是白烧
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val (tw, th) = TinyThumb.targetSize(bounds.outWidth, bounds.outHeight)
            if (tw <= 0 || th <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val small = Bitmap.createScaledBitmap(src, tw, th, true)
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, TinyThumb.QUALITY, out)
            TinyThumb.dataUri(out.toByteArray())
        }.getOrNull()
    }

    /** 解码到「至少还有 20px 见方」就够——再大只是多烧内存。2 的幂，`inSampleSize` 的要求。 */
    private fun sampleSizeFor(w: Int, h: Int): Int {
        var s = 1
        while (w / (s * 2) >= TinyThumb.MAX_SIDE && h / (s * 2) >= TinyThumb.MAX_SIDE) s *= 2
        return s
    }
}
