package com.libeyond.imandroid.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.libeyond.imandroid.sdk.logging.IMLog
import java.io.File

/**
 * 把一张位图交出去：存相册 / 走系统分享（「我」页二维码用）。
 *
 * 与 [com.libeyond.imandroid.ui.components.qrToBitmap] 分工：那边负责画，这边负责落地。
 * 两边都不碰网络，也不知道二维码是什么——换成别的图一样能用。
 */
object ImageExport {

    private val log = IMLog.tag("IM.Export")

    /** 相册里的相册名 / 分享缓存目录名。 */
    private const val ALBUM = "IM"

    /**
     * 保存到相册。返回**给用户看的**结果文案（成功与失败都有话说，不静默）。
     *
     * Android 10（Q）起走 MediaStore 的分区存储，**不需要任何权限**；
     * 更早的系统需要 `WRITE_EXTERNAL_STORAGE`，由调用方先要到再进来
     * （见 manifest 里那条 `maxSdkVersion=28` 的声明）。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String): String {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "保存失败：相册不可写"
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return "保存失败：无法写入"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            log.i("qr_saved_to_gallery")
            "已保存到相册"
        } catch (e: Exception) {
            // 权限被拒、存储满、厂商 ROM 拦截都会走到这里——必须给出可行动的提示而不是静默。
            log.w("gallery_save_failed", "err" to e.javaClass.simpleName)
            "保存失败，请检查存储权限"
        }
    }

    /**
     * 走系统分享：图片 + 文本（对齐 iOS `UIActivityViewController` 那两个 activityItems）。
     *
     * 图片必须经 FileProvider 交出去——直接给 `file://` 从 Android 7 起就会
     * `FileUriExposedException` 崩溃。
     *
     * @return 失败时给用户看的文案；成功返回 null。
     */
    fun shareImage(context: Context, bitmap: Bitmap, text: String, fileName: String): String? {
        return try {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, fileName)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "分享二维码").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        } catch (e: Exception) {
            log.w("share_failed", "err" to e.javaClass.simpleName)
            "分享失败"
        }
    }
}
