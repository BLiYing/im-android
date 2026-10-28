package com.libeyond.imandroid.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * 导出 ZIP 的落地点（SAF 文档，target = Uri.toString）。SAF / contentResolver 只出现在这里：
 * 下载器（`IMClient`，构造时注入）与页面（`ExportDataHost` 丢弃空壳）共用，不各写一份。
 */
open class ExportSink(private val ctx: Context) {
    private val log = IMLog.tag("IM.Export")

    /** 打开 [target] 写入；流在成功 / 失败 / 取消时都由 `use` 关闭。 */
    open suspend fun <T> write(target: String, block: suspend (OutputStream) -> T): T = withContext(Dispatchers.IO) {
        val out = ctx.contentResolver.openOutputStream(Uri.parse(target), "w")
            ?: throw IOException("openOutputStream null")
        out.use { block(it) }
    }

    /**
     * 失败 / 取消后清掉空壳或半截文件。先清成 0 字节再删：个别厂商（ColorOS）的文档提供方 `deleteDocument`
     * 返回成功却把文件留在原处，只删的话会留下半截 zip。
     */
    open suspend fun discard(target: String) {
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(target)
            truncate(uri)
            val deleted = runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                .onFailure { log.w("export_partial_delete_failed", "err" to it.javaClass.simpleName) }
                .getOrDefault(false)
            log.i("export_partial_discard", "deleted" to deleted)
        }
    }

    private fun truncate(uri: Uri) {
        runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.close() }
            .onFailure { log.w("export_partial_truncate_failed", "err" to it.javaClass.simpleName) }
    }
}
