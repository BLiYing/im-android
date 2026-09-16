package com.libeyond.imandroid.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.libeyond.imandroid.data.MediaSaveName
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把查看器里这一条媒体存进系统相册。
 *
 * 与 [ImageExport] 分工：那边吃的是**已经在内存里的 [android.graphics.Bitmap]**（二维码），
 * 这边吃的是**一个地址**，要先把字节弄到手——所以不是复用而是另一条路。
 * 共用的只有 MediaStore 那套 Q 前后分支的写法与相册子目录名。
 *
 * 视频不能先读进 `ByteArray`：一段 4K 长视频几百 MB，整包进内存就是 OOM
 * （发送侧为此专门走了分片，存的时候当然也不能倒回去）。这里全程流式拷贝。
 */
internal object MediaSaver {

    private val log = IMLog.tag("IM.Export")

    /** 与二维码存图落在同一个相册子目录下。 */
    private const val ALBUM = "IM"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * 保存。返回**给用户看的文案**（成功失败都有话说，不静默）。
     *
     * @param url 绝对 URL，或待发消息那条本地 `content://`
     */
    suspend fun save(context: Context, url: String, isVideo: Boolean): String =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext "这条媒体没有地址"
            val name = MediaSaveName.fileNameFor(url, isVideo, System.currentTimeMillis())
            val mime = MediaSaveName.mimeFor(name, isVideo)
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$ALBUM")
                    // 写完之前对相册不可见——否则用户会在相册里看到一条正在长大的半截文件
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val target = try {
                resolver.insert(collection, values)
            } catch (e: Exception) {
                log.w("media_save_insert_failed", "err" to e.javaClass.simpleName)
                null
            } ?: return@withContext "保存失败：相册不可写"

            try {
                openSource(context, url).use { input ->
                    resolver.openOutputStream(target)?.use { out -> input.copyTo(out) }
                        ?: throw IllegalStateException("openOutputStream returned null")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(target, values, null, null)
                }
                log.i("media_saved", "video" to isVideo)
                "已保存到相册"
            } catch (e: Exception) {
                // **失败必须把占位行删掉**：不删就在相册里留一条 0 字节、点开是黑屏的条目，
                // 用户不会认为是「保存失败」，只会认为「这个 App 存出来的东西是坏的」。
                runCatching { resolver.delete(target, null, null) }
                log.w("media_save_failed", "err" to e.javaClass.simpleName)
                "保存失败，请检查网络与存储空间"
            } catch (e: OutOfMemoryError) {
                runCatching { resolver.delete(target, null, null) }
                log.w("media_save_oom")
                "保存失败：文件太大"
            }
        }

    /**
     * 本地 `content://` 直接读；否则走 HTTP。
     *
     * **`internal` 是因为 [CopyImage] 也要它**：「复制图片」与「存相册」拿字节的方式必须是同一条——
     * 各搓一份的话，哪天服务端给媒体加上鉴权（见下方注释），只会有一条路被补上 Authorization。
     */
    internal fun openSource(context: Context, url: String): InputStream {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return context.contentResolver.openInputStream(Uri.parse(url))
                ?: throw IllegalStateException("openInputStream returned null")
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        // /uploads 是公开可读的（Coil 加载它时也不带任何头），所以这里不需要带 token。
        // 一旦服务端给媒体加上鉴权，这里会静默变成 401 → 走到 catch 里报「保存失败」，
        // 到那天要回来补 Authorization。
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        return conn.inputStream
    }
}
