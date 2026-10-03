package com.libeyond.imandroid.data

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 待发媒体的**应用私有副本**（对齐 iOS `IMPendingMediaStore`，落点 `filesDir/pending_media/`）。
 *
 * 为什么要复制一份：系统相册 / 文件选择器给的 `content://` 流**只能向前读**、读权限还随发起进程一起没了——
 * 暂停后续传要随机读，杀进程后要还能重试，都靠这一份自己的字节。**不放 cache 目录**（系统会在空间紧张时清掉，
 * 一清就是「本地文件已丢失」）。语音有自己的 `voice_pending/`，不在这里。
 *
 * 配套的 `.uploadid` 旁路文件存分片上传的 `upload_id`，**与副本同生共死**：副本删（取消/发送成功）它就删，
 * 副本在它就在——下次启动才能 `status` 续传。
 */
class PendingMediaStore(private val dir: File) {

    /** 给这条消息分一个副本路径（扩展名取自原文件名，服务端按扩展名过白名单）。 */
    fun newFile(clientMsgId: String, fileName: String): File {
        dir.mkdirs()
        val ext = fileName.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(8)
        return File(dir, if (ext.isEmpty()) clientMsgId else "$clientMsgId.$ext")
    }

    /** 副本的 `file://` 引用——待发行的正文就存它（[isLocalUri] 认这个前缀）。 */
    fun refOf(f: File): String = "file://" + f.path

    /** 正文是不是**本仓**的私有副本（`file://` 且在本目录下）；语音等别的 `file://` 返回 null。 */
    fun fileOf(content: String): File? {
        if (!content.startsWith("file://")) return null
        val f = File(content.removePrefix("file://"))
        val root = runCatching { dir.canonicalPath }.getOrNull() ?: return null
        val path = runCatching { f.canonicalPath }.getOrNull() ?: return null
        return f.takeIf { path.startsWith(root + File.separator) }
    }

    /** 流式复制（不整个读进内存）；失败/读不到返回 false，调用方标失败并删掉半截文件。 */
    suspend fun copyIn(resolver: ContentResolver, uri: Uri, target: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext false
            input.use { i -> target.outputStream().use { o -> i.copyTo(o, COPY_BUFFER) } }
            target.length() > 0
        } catch (e: java.io.IOException) {
            target.delete()
            false
        }
    }

    /** [copyIn] 的流版本（调用方只有 openStream 时用）。 */
    suspend fun copyFrom(open: () -> java.io.InputStream?, target: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val input = open() ?: return@withContext false
            input.use { i -> target.outputStream().use { o -> i.copyTo(o, COPY_BUFFER) } }
            target.length() > 0
        } catch (e: java.io.IOException) {
            remove(target)
            false
        }
    }

    fun uploadIdOf(f: File): String? = sidecar(f).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** 空串 = 清掉（会话失效 / 传完）。 */
    fun setUploadId(f: File, id: String) {
        val s = sidecar(f)
        if (id.isEmpty()) s.delete() else s.writeText(id)
    }

    /** 取消 / 发送成功：副本与旁路文件一起删。 */
    fun remove(f: File) {
        f.delete()
        sidecar(f).delete()
    }

    private fun sidecar(f: File) = File(f.path + ".uploadid")

    private companion object {
        const val COPY_BUFFER = 256 * 1024
    }
}
