package com.libeyond.imandroid.data

import android.graphics.Bitmap
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.libeyond.mediapicker.MediaCompressor
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

    /** 从本地文件生成（老消息补种缩略用）。读不出来返回 null。 */
    fun fromFile(file: java.io.File?): String? {
        if (file == null || !file.isFile || file.length() <= 0) return null
        // 只读**头**拿尺寸不必整读；但这里要真解码，故先看大小——几百 MB 的"图片"多半是坏数据
        if (file.length() > 64L * 1024 * 1024) return null
        return runCatching { fromImageBytes(file.readBytes()) }.getOrNull()
    }

    /**
     * 从 `content://` 原图生成（「原图」流式发送不把字节整个读进内存，所以拿不到 bytes）。
     * 按 EXIF 方向转正再缩：原图字节带着 EXIF 原样发出去，收端会转正显示，占位必须同向。
     */
    fun fromUri(context: Context, uri: Uri): String? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val rotation = MediaCompressor.exifRotation(context, uri)
        val (dw, dh) = MediaCompressor.displaySize(bounds.outWidth, bounds.outHeight, rotation)
        val (tw, th) = TinyThumb.targetSize(dw, dh)
        if (tw <= 0 || th <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val oriented = if (rotation != 0) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else {
            src
        }
        encodeTiny(oriented, tw, th)
    }.getOrNull()

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
            encodeTiny(src, tw, th)
        }.getOrNull()
    }

    private fun encodeTiny(src: Bitmap, tw: Int, th: Int): String? {
        val small = Bitmap.createScaledBitmap(src, tw, th, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, TinyThumb.QUALITY, out)
        return TinyThumb.dataUri(out.toByteArray())
    }

    /** 解码到「至少还有 20px 见方」就够——再大只是多烧内存。2 的幂，`inSampleSize` 的要求。 */
    private fun sampleSizeFor(w: Int, h: Int): Int {
        var s = 1
        while (w / (s * 2) >= TinyThumb.MAX_SIDE && h / (s * 2) >= TinyThumb.MAX_SIDE) s *= 2
        return s
    }
}
