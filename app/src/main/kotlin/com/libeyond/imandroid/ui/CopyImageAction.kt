package com.libeyond.imandroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import com.libeyond.imandroid.data.MediaSaveName
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 复制一张图片到剪贴板（查看器「更多 → 复制」，对齐 iOS `copyMessageToPasteboard:`）。
 *
 * ### 与 iOS 的差别，说清楚
 * iOS 复制的是 `UIImage` 字节，粘到哪里都是图。本端复制的是**图片文件的 `content://`**
 * （`ClipData.newUri`）：粘进相册/聊天/邮件这类认 URI 的地方是同一张图，粘进纯文本框会得到一段 URI。
 * 安卓没有"把位图放进剪贴板"的通用做法，各家输入法认的都是 URI，所以这是本端能给的最好语义。
 *
 * ### 为什么先复制进 `cache/share`
 * `res/xml/file_paths.xml` 的白名单**只开了 `share/` 与 `camera/`**——把整个缓存目录开出去
 * 等于允许任何拿到 `content://` 的应用读我们全部消息媒体。要交出去的东西先复制进 share/
 * （与 [OpenFile]、[ImageExport] 同一条约定）。
 */
internal object CopyImage {

    private val log = IMLog.tag("IM.Export")

    /**
     * @param url 绝对 URL，或待发消息那条本地 `content://`。
     * @return 给用户看的文案（成功失败都有话说，不静默）。
     */
    suspend fun copy(context: Context, url: String): String = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext "这张图片没有地址"
        try {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, MediaSaveName.fileNameFor(url, isVideo = false, nowMs = System.currentTimeMillis()))
            // 取字节这一步与「存相册」共用（MediaSaver.openSource）：本地 content:// 直接读，否则走 HTTP。
            // 各搓一份的话，哪天媒体加了鉴权只会有一条路被补上。
            MediaSaver.openSource(context, url).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
            val clip = ClipData.newUri(context.contentResolver, "图片", uri)
            withContext(Dispatchers.Main) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            }
            log.i("image_copied")
            "已复制图片"
        } catch (e: Exception) {
            // 网络失败、磁盘满、厂商 ROM 拦剪贴板都会到这——如实说，别静默
            log.w("image_copy_failed", "err" to e.javaClass.simpleName)
            "复制失败，请检查网络"
        } catch (e: OutOfMemoryError) {
            log.w("image_copy_oom")
            "复制失败：图片太大"
        }
    }
}
