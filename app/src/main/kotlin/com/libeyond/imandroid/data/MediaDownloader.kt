package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 媒体下载的**统一编排**（M4-7，对齐 iOS `IMMediaDownloadCoordinator`）。
 *
 * 只有一个入口，气泡 / 宫格逐格 / 文件气泡 / 详情页宫格四处共用同一份状态。
 * 不共用的话，同一条媒体在两个界面上会各下各的、进度也各显各的。
 *
 * ### 三条实现纪律
 * 1. **写 `.part`，下完才改名**——半截文件被当成"已就绪"渲染出来是最难查的一类问题；
 * 2. **404/410 判「失效」而不是「失败」**：服务端已清理的东西给重试就是每点一次拉一次 404，
 *    所以失效是终态，且**记在内存里**（同一会话内不再回源，掐 404 风暴）；
 * 3. **暂停 = 取消协程 + 删 `.part`**：本端**没有断点续传**，暂停后再点是从头开始。
 *    这一条要在界面上说清楚（iOS 有续传，本端没有——差异档记着）。
 */
class MediaDownloader(
    private val scope: CoroutineScope,
    private val cache: MediaCache,
    /** 相对地址 → 绝对地址（带当前 host）。 */
    private val absolute: (String) -> String,
    private val tokenProvider: () -> String?,
) {
    private val log = IMLog.tag("IM.Download")

    private val ok = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** url → 状态。UI 直接 collect；**没有条目 = 还没问过**，由调用方按"在不在本地"定初值。 */
    val states: StateFlow<Map<String, DownloadState>> = _states

    private val jobs = HashMap<String, Job>()

    /** 本会话内已知失效的地址——不再回源（iOS `IMMediaExpiryRegistry` 的内存态那半）。 */
    private val expired = HashSet<String>()

    /** 某条媒体此刻的状态。本地已有 → Ready；已知失效 → Expired；否则看有没有在途记录。 */
    fun stateOf(url: String, isVideo: Boolean = false): DownloadState {
        if (url.isBlank()) return DownloadState(DownloadPhase.NotStarted)
        _states.value[url]?.let { if (it.phase != DownloadPhase.Ready) return it }
        if (cache.isReady(url, isVideo)) return DownloadState(DownloadPhase.Ready)
        if (url in expired) return DownloadState(DownloadPhase.Expired)
        return _states.value[url] ?: DownloadState(DownloadPhase.NotStarted)
    }

    fun localFile(url: String, isVideo: Boolean = false) =
        cache.fileFor(url, isVideo).takeIf { it.isFile && it.length() > 0 }

    /** 开始 / 重试。已在途或已就绪时是 no-op（重复点不会下两遍）。 */
    fun start(url: String, isVideo: Boolean = false, expectedBytes: Long = 0) {
        if (url.isBlank() || url in expired) return
        if (cache.isReady(url, isVideo)) return
        if (jobs[url]?.isActive == true) return
        put(url, DownloadState(DownloadPhase.Downloading, 0, expectedBytes))
        jobs[url] = scope.launch { run(url, isVideo, expectedBytes) }
    }

    /** 暂停：取消协程并删掉半截文件（**没有续传**，见类注释）。 */
    fun pause(url: String, isVideo: Boolean = false) {
        jobs.remove(url)?.cancel()
        runCatching { cache.partFor(url, isVideo).delete() }
        put(url, DownloadState(DownloadPhase.Paused))
    }

    private fun put(url: String, s: DownloadState) {
        _states.value = _states.value + (url to s)
    }

    private suspend fun run(url: String, isVideo: Boolean, expectedBytes: Long) {
        val part = cache.partFor(url, isVideo)
        val dst = cache.fileFor(url, isVideo)
        try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(absolute(url))
                    .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
                    .get()
                    .build()
                ok.newCall(req).execute().use { resp ->
                    if (resp.code == 404 || resp.code == 410) {
                        // 服务端已清理 —— 终态，不给重试
                        expired += url
                        put(url, DownloadState(DownloadPhase.Expired))
                        log.i("media_expired", "code" to resp.code)
                        return@use
                    }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: expectedBytes
                    part.parentFile?.mkdirs()
                    var received = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                received += n
                                put(url, DownloadState(DownloadPhase.Downloading, received, total))
                            }
                        }
                    }
                    // **下完才改名**：半截 .part 绝不能被当成已就绪
                    if (received <= 0) throw IOException("zero bytes")
                    runCatching { dst.delete() }
                    if (!part.renameTo(dst)) throw IOException("rename failed")
                    put(url, DownloadState(DownloadPhase.Ready, received, total))
                    log.i("media_download_ok", "bytes" to received)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 暂停走的就是这条：状态已由 pause() 置好，别覆盖成 Failed
            runCatching { part.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { part.delete() }
            put(url, DownloadState(DownloadPhase.Failed))
            log.w("media_download_failed", "err" to (e.message ?: e::class.simpleName ?: "?"))
        } finally {
            jobs.remove(url)
        }
    }
}
