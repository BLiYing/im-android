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
     * 放进剪贴板的那一项：**同时带 URI 与纯文本两种表示**。
     *
     * ### 为什么不能只用 `ClipData.newUri`
     * 它的 `ClipDescription` 只声明图片类 MIME，**不含 `text/plain`**。而 Compose 的
     * （⚠️ 注释里别写「斜杠 + 星号」这种通配 MIME 字面量：Kotlin 的块注释**可嵌套**，
     *   那两个字符会当场开一层内层注释，本段的结束符只关掉里层、外层一路吞到文件尾，
     *   而编译器报的却是「Missing '}'」。2026-09-17 在这上面栽过一次。）
     * `BasicTextField` 判断"能不能粘贴"走的是 `ClipboardManager.hasText()`，那个方法只看
     * description 里有没有文本类 MIME——于是复制完图片回到自家输入框长按，
     * 系统菜单里根本不出现「粘贴」，看着像**长按没反应**
     * （2026-09-17 用户报；iOS 那侧是 `UIPasteboard.image` + 重写 `canPerformAction:`，
     * 系统天然认得图片剪贴项，本端没有这个通道）。
     *
     * 补一条文本表示之后：认 URI 的应用（相册/聊天/邮件）拿到的仍是同一张图，
     * 只认文本的输入框拿到一段 `content://…`——而那正是 [com.libeyond.imandroid.data.PasteImage]
     * 认领并还原成待发图的形状。两条路合起来才等价于 iOS 的「粘到哪儿都是图」。
     */
    private fun imageClip(context: Context, uri: android.net.Uri): ClipData {
        val mime = context.contentResolver.getType(uri) ?: "image/*"
        val desc = android.content.ClipDescription(
            "图片",
            arrayOf(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN, mime),
        )
        return ClipData(desc, ClipData.Item(uri.toString(), null, uri))
    }

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
            val clip = imageClip(context, uri)
            withContext(Dispatchers.Main) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            }
            log.i("image_copied")
            "已复制图片"
        } catch (e: kotlinx.coroutines.CancellationException) {
            // **必须原样抛回去**：下面那个 `catch (Exception)` 会把取消也吞掉，于是"协程被取消"
            // 被记成一条 `image_copy_failed`，看起来像复制失败、实际是调用方那一层已经离开组合
            // （2026-09-17 真机抓到的正是 `LeftCompositionCancellationException`）。
            // 吞掉取消还会破坏取消传播，这是本仓 `runCatchingCancellable` 存在的同一个理由。
            throw e
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
