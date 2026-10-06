package com.libeyond.mediapicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * 发图前的压缩。口径**照抄 iOS**（`IMMediaPicker`：长边 ≤2048、JPEG 0.8），
 * 三端发出去的图应该是一个量级，不然同一张图在 iOS 上 800KB、在 Android 上 6MB。
 *
 * 「原图」开关关掉这一步，直传原始字节。
 *
 * ### 两个必须做对的细节
 * 1. **两趟解码**：先 `inJustDecodeBounds` 读尺寸算 `inSampleSize`，再真解码。
 *    一趟直接解全图，8000×6000 的照片解出来是 **183MB** 的 ARGB_8888 位图，必 OOM。
 * 2. **EXIF 方向**：相机拍的照片字节里往往是横的，靠 EXIF 标记转 90°。
 *    重新编码会**丢掉 EXIF**，所以必须在编码前把像素真的转过来——
 *    漏了这步的表现是「相册里是正的，发出去躺倒了」。
 */
object MediaCompressor {

    /** 长边上限（与 iOS 同值）。 */
    const val MAX_EDGE = 2048

    /** JPEG 质量（iOS 用 0.8）。 */
    const val QUALITY = 80

    /**
     * 算 `BitmapFactory.Options.inSampleSize`：**2 的幂**，且保证降采样后长边仍 ≥ [maxEdge]
     * （宁可解得比目标大一点再精确缩放，也不要解得比目标小——那样放大回去就糊了）。
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** 等比缩放到长边 = [maxEdge]；本来就小于上限则**原样不放大**。 */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val ratio = maxEdge.toDouble() / longest
        // 至少 1px：极端长条图（如 20000×3）缩完短边会算成 0，创建位图直接抛
        return maxOf(1, Math.round(width * ratio).toInt()) to maxOf(1, Math.round(height * ratio).toInt())
    }

    /** EXIF 方向标记 → 需要旋转的角度。翻转类（mirror）也一并归到最接近的旋转。 */
    fun rotationOf(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /**
     * 压缩一张图。失败返回 null（调用方回落原图或跳过）。**必须在 IO 线程调用。**
     */
    fun compressImage(
        context: Context,
        uri: Uri,
        maxEdge: Int = MAX_EDGE,
        quality: Int = QUALITY,
        log: MediaPickerLog = MediaPickerLog.None,
    ): ByteArray? {
        val cr = context.contentResolver
        return try {
            // 第 1 趟：只读尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                log.w("compress_bounds_unreadable")
                return null
            }
            // 第 2 趟：按 inSampleSize 真解
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: run {
                    log.w("compress_decode_failed")
                    return null
                }
            // EXIF 方向必须在编码前落到像素上——重新编码会丢掉 EXIF 标记
            val rotation = try {
                cr.openInputStream(uri)?.use { rotationOf(ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )) } ?: 0
            } catch (e: Exception) {
                log.w("compress_exif_failed", "err" to e.javaClass.simpleName)
                0
            }
            val (tw, th) = targetSize(decoded.width, decoded.height, maxEdge)
            var out = if (tw != decoded.width || th != decoded.height) {
                Bitmap.createScaledBitmap(decoded, tw, th, true)
            } else {
                decoded
            }
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
                if (rotated !== out) { out.takeIf { it !== decoded }?.recycle(); out = rotated }
            }
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            if (out !== decoded) out.recycle()
            decoded.recycle()
            bos.toByteArray().also {
                log.d("compress_done", "from" to "${bounds.outWidth}x${bounds.outHeight}", "bytes" to it.size)
            }
        } catch (e: OutOfMemoryError) {
            // 位图 OOM 是 Error 不是 Exception，catch(Exception) 抓不到
            log.w("compress_oom")
            null
        } catch (e: Exception) {
            log.w("compress_failed", "err" to e.javaClass.simpleName)
            null
        }
    }

    /** 旋转 90/270 度后宽高互换（EXIF 方向 5~8 里需要换的那几种）。纯算术，可单测。 */
    fun displaySize(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) height to width else width to height

    /** EXIF 方向对应的旋转角；读不到（非 JPEG/HEIC、流打不开）一律 0。 */
    fun exifRotation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            rotationOf(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } ?: 0
    } catch (e: Exception) {
        0
    }

    /**
     * 图片**显示方向**的像素尺寸（不解码像素，几乎零成本）。协议要 `media_w`/`media_h` 让对端按原比例预留气泡。
     *
     * `BitmapFactory` 的 `inJustDecodeBounds` 给的是**存储方向**的宽高，不看 EXIF：
     * 竖拍的原图（存成 4000×3000 + 旋转 90 标记）会被报成横的，对端按 4:3 预留、
     * 图加载出来是 3:4，整个气泡跳一下。所以这里要按 EXIF 换算一次。
     * 「原图」模式字节不重新编码、EXIF 原样带着走，这条路径才会踩到；压缩路径已把朝向落到像素上。
     */
    fun imageSize(context: Context, uri: Uri): Pair<Int, Int>? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0 && o.outHeight > 0) {
            displaySize(o.outWidth, o.outHeight, exifRotation(context, uri))
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** 读**已编码字节**的像素尺寸（压缩后要重新量一次，压完的宽高和原图不同；压缩产物无 EXIF，不用换算）。 */
    fun imageSizeOf(bytes: ByteArray): Pair<Int, Int>? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    } catch (e: Exception) {
        null
    }

    /**
     * 压缩后**统一是 JPEG**，文件名与 MIME 必须跟着改。
     *
     * 不改的结果很隐蔽：服务端按扩展名 `.png` 落盘并回 `content_type=image`，
     * 字节却是 JPEG——多数解码器能容忍，但 `.heic` 这种就直接废了
     * （名字说 heic、字节是 jpeg，对端按 heic 解必然失败）。
     */
    fun jpegNameFor(original: String): String {
        val base = original.substringBeforeLast('.', original).ifBlank { "image" }
        return "$base.jpg"
    }
}
