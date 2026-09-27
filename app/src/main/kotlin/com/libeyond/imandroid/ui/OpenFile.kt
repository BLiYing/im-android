package com.libeyond.imandroid.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.logging.IMLog
import java.io.File

/**
 * 打开一个**已下载到本地**的文件（对齐 iOS 的 `IMFilePreviewPresenter`：
 * 文件行/文件气泡在「就绪」态点一下就是打开它）。
 *
 * ### 为什么要先复制到 `cache/share`
 * `res/xml/file_paths.xml` 的白名单**只开了 `share/` 与 `camera/`**，注释里写着理由：
 * 把整个 `files-path` 开出去等于允许任何拿到 `content://` 的应用读我们全部消息媒体。
 * 所以照既有约定走——**要交出去的东西先复制进 `share/`**（`ImageExport.shareImage` 同款）。
 *
 * 复制的代价是一份临时副本；相比放开整个媒体目录，这个代价是划算的。
 */
object OpenFile {

    private val log = IMLog.tag("IM.File")

    /**
     * @param displayName 给人看的文件名——**用它推 MIME 与命名副本**，
     *   不要用缓存里那个 SHA-1 文件名（`3da1b9….jpg` 交给别的应用毫无意义）。
     * @return 失败时给用户看的文案；成功返回 null。
     */
    fun open(context: Context, file: File, displayName: String): String? {
        if (!file.isFile || file.length() <= 0) return Str.s(R.string.chat_file_not_downloaded)
        return try {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, safeName(displayName))
            file.copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(displayName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        } catch (e: android.content.ActivityNotFoundException) {
            // 没装能打开 .xlsx/.psd 的应用是常态，不是错误——如实说，别报"打开失败"
            log.i("file_open_no_app")
            Str.s(R.string.chat_file_no_app_to_open)
        } catch (e: Exception) {
            log.w("file_open_failed", "err" to (e.javaClass.simpleName))
            Str.s(R.string.chat_file_open_failed)
        }
    }

    /**
     * 按扩展名推 MIME。
     *
     * **刻意不复用 `MediaSaveName.mimeFor`**：那个是"存相册"用的，
     * 入参就带着 `isVideo`（大类已知，扩展名只挑子类型），
     * 拿它去问 `.xlsx` 会得到 `image/jpeg`。这里什么类型都可能，走系统表。
     *
     * 查不到给通配 MIME——让系统弹选择器，比塞一个错的类型（直接说"无法打开"）强。
     */
    internal fun mimeOf(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "*/*"
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /**
     * 副本文件名。**扒掉路径分隔符**——服务端给的名字里带 `/` 时会写到 `share/` 之外去。
     * 空名兜一个，否则 `File(dir, "")` 指向目录本身。
     */
    internal fun safeName(displayName: String): String =
        displayName.replace('/', '_').replace('\\', '_').trim().ifBlank { "file" }
}
