package com.libeyond.imandroid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.libeyond.imandroid.sdk.logging.IMLog
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 把相册里选中的图片**规整成能当头像上传的字节**：居中方裁 + 缩到 [SIDE] + JPEG 压到 2MB 内。
 *
 * 为什么必须在客户端做：服务端头像接口只收 **≤2MB 的 jpg/png**，而现在随手一张
 * 照片就 5–10MB。不规整就直接上传 = 用户选了张正常照片却被拒，且看不出为什么。
 *
 * 与 iOS 的差异（**已知欠账**）：iOS 是选图后进 `IMAvatarCropViewController` 让用户
 * 自己框，本端是**自动居中方裁**。人像照片主体多在中间，居中裁通常没问题，
 * 但竖幅全身照会裁掉头——裁切页是接下来该补的（见 current_task 的欠账）。
 */
object AvatarPrepare {

    /** 输出边长。头像最大展示尺寸是 96dp，560dpi 下约 336px，512 足够且留了余量。 */
    private const val SIDE = 512

    /** 服务端上限 2MB；留一点余量，别卡在边界上。 */
    private const val MAX_BYTES = 1_800_000

    private val log = IMLog.tag("IM.Avatar")

    /** @return JPEG 字节；解不出图（选到损坏文件 / 不支持的格式）时返回 null。 */
    fun fromUri(context: Context, uri: Uri): ByteArray? {
        val src = decodeSampled(context, uri) ?: return null
        val square = centerCropSquare(src)
        val scaled =
            if (square.width == SIDE) square
            else Bitmap.createScaledBitmap(square, SIDE, SIDE, true)

        // 逐档降质量，直到落进上限。512×512 的 JPEG 在 90 质量下通常就几十 KB，
        // 这个循环几乎总是第一轮就退出；留着是为了不给「某些设备存出巨大 JPEG」留口子。
        for (quality in intArrayOf(90, 80, 70, 60)) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_BYTES) return bytes
        }
        log.w("avatar_still_too_large")
        return null
    }

    /**
     * 先读边界再按 inSampleSize 解码：**不能整张解进内存**——
     * 一张 4000×3000 的照片按 ARGB_8888 解是 48MB，几张就 OOM。
     */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        log.w("avatar_decode_failed", "err" to e.javaClass.simpleName)
        null
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        if (side <= 0) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return if (x == 0 && y == 0 && src.width == src.height) src
        else Bitmap.createBitmap(src, x, y, side, side)
    }
}
