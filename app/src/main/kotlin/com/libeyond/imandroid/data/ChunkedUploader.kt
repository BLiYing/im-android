package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.UploadResult
import com.libeyond.imandroid.sdk.api.UploadTransport
import com.libeyond.imandroid.sdk.http.ApiException
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * **可暂停 / 续传 / 失败重试**的分片上传（对齐 iOS `IMChunkedUploadTask`）。
 * 数据来自一个**应用私有副本**（[file]，可随机读）——这是续传的前提：系统相册的 `content://` 流只能向前读、
 * 读权限还随进程消亡。传输走 [UploadTransport]（真实现 `UploadApi`，单测里是内存假服务端）。
 *
 * ### 状态机（单链）
 * 一个协程从头跑到尾，**不存在「并行第二条链」**（iOS 用 generation 计数防这个，协程里就是结构化的）：
 * - **暂停**：置标志并**掐掉在途那一片的请求**（一片最大 8MB，只作废回调不掐请求等于白占带宽）；
 *   循环在下一轮顶部挂起等恢复；
 * - **恢复**：先问服务端 `status` 取 offset（**服务端 offset 是唯一真相**），再从那里继续；
 * - **取消**：外层协程被取消就是取消；
 * - **完成** `complete` 不受暂停影响——这时已经全部传完。
 *
 * ### 出错分流（`handle`）
 * - 业务错（会话过期/不存在等，24h 服务端回收）：清掉 `upload_id`、`init` 新会话、从 0 重来，**最多 [MAX_RESTARTS] 次**
 *   （iOS 踩过「重试 → status 400 → 标失败 → 再重试」的死循环）；
 * - 网络错：会话还在、offset 在服务端，等 [NET_BACKOFF_MS] 后接着传，**连续最多 [MAX_NET_RETRIES] 次**
 *   （任一片成功就清零），再失败就抛出去让用户点重试（重试仍从服务端 offset 续）；
 * - 本地读文件失败 / 0 字节：终态。
 */
class ChunkedUploader(
    private val transport: UploadTransport,
    private val file: File,
    private val fileName: String,
    private val mime: String,
    /** 之前落盘的 `upload_id`（有 = 先 `status` 续传；null = 新开）。 */
    initialUploadId: String?,
    /** 会话 id 变化（新开 = 新 id；会话失效 = 空串表示清除）：调用方负责持久化。 */
    private val onUploadId: (String) -> Unit,
    private val onProgress: (sent: Long, total: Long) -> Unit,
    private val backoff: suspend (Long) -> Unit = { delay(it) },
    /** 读文件的线程池；单测里换成同步的，免得 runTest 等不到真实 IO 线程。 */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private var uploadId: String? = initialUploadId?.takeIf { it.isNotEmpty() }
    @Volatile private var inFlight: Deferred<*>? = null

    /** 暂停：置标志 + 掐掉在途那片。已在 `complete` 阶段则无效果。 */
    fun pause() {
        _paused.value = true
        inFlight?.cancel()
    }

    fun resume() {
        _paused.value = false
    }

    suspend fun run(): UploadResult {
        val total = file.length()
        if (total <= 0L) throw ApiException(ApiException.TRANSPORT, "文件为空或读不到")
        var restarts = 0
        var netRetries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return attempt(total) { netRetries = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isTransport) {
                    if (netRetries >= MAX_NET_RETRIES) throw e
                    netRetries++
                    backoff(NET_BACKOFF_MS) // 会话与 offset 都还在服务端，接着传
                } else {
                    if (restarts >= MAX_RESTARTS) throw e
                    restarts++
                    uploadId = null
                    onUploadId("") // 死 id 必须从持久化里清掉，否则每次启动都撞同一个 400
                }
            }
        }
    }

    private suspend fun attempt(total: Long, onChunkOk: () -> Unit): UploadResult {
        var chunkSize = DEFAULT_CHUNK
        var offset: Long
        val existing = uploadId
        if (existing != null) {
            offset = transport.sessionStatus(existing)
        } else {
            val s = transport.sessionInit(fileName, total)
            uploadId = s.id
            onUploadId(s.id)
            offset = s.offset
            if (s.chunkSize > 0) chunkSize = s.chunkSize
        }
        val id = uploadId!!
        onProgress(offset, total)
        val buf = ByteArray(chunkSize)
        while (offset < total) {
            awaitResumed()
            val len = minOf(chunkSize.toLong(), total - offset).toInt()
            readAt(offset, buf, len)
            val next: Long = try {
                coroutineScope {
                    val d = async(start = CoroutineStart.UNDISPATCHED) { transport.sessionChunk(id, offset, buf, len, mime) }
                    inFlight = d
                    try { d.await() } finally { inFlight = null }
                }
            } catch (e: CancellationException) {
                // 外层真被取消 → 照抛；只是暂停掐掉了这一片 → 等恢复，再问服务端 offset 对齐
                if (!currentCoroutineContext().isActive || !_paused.value) throw e
                awaitResumed()
                offset = transport.sessionStatus(id)
                onProgress(offset, total)
                continue
            }
            // 服务端回的是它**当前**已收长度——对不上自己会对齐，以它为准继续（不报错、不重发整段）
            offset = next
            onChunkOk()
            onProgress(offset, total)
        }
        return transport.sessionComplete(id)
    }

    private suspend fun awaitResumed() {
        if (_paused.value) _paused.first { !it }
    }

    private suspend fun readAt(offset: Long, buf: ByteArray, len: Int) = withContext(io) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                var filled = 0
                while (filled < len) {
                    val n = raf.read(buf, filled, len - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < len) throw ApiException(ApiException.TRANSPORT, "文件读取不完整")
            }
        } catch (e: java.io.IOException) {
            // 本地读不了是终态，不能当网络错去重试
            throw LocalReadFailed(e)
        }
    }

    /** 本地文件读失败（副本被删/磁盘坏）：终态，调用方标失败「本地文件丢失」。 */
    class LocalReadFailed(cause: Throwable) : RuntimeException("local file read failed", cause)

    companion object {
        const val MAX_RESTARTS = 2
        const val MAX_NET_RETRIES = 2
        const val NET_BACKOFF_MS = 2_000L
        const val DEFAULT_CHUNK = 8 shl 20
    }
}
