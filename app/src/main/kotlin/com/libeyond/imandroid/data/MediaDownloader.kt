package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
 * ### 四条实现纪律
 * 1. **写 `.part`，下完才改名**——半截文件被当成"已就绪"渲染出来是最难查的一类问题；
 * 2. **404/410 判「失效」而不是「失败」**：服务端已清理的东西给重试就是每点一次拉一次 404，
 *    所以失效是终态，且**记在内存里**（同一会话内不再回源，掐 404 风暴）；
 * 3. **暂停 = 取消协程 + 删 `.part`**：本端**没有断点续传**，暂停后再点是从头开始。
 *    这一条要在界面上说清楚（iOS 有续传，本端没有——差异档记着）；
 * 4. **只有登记在册的任务才写状态**：暂停 / 清缓存都是先把任务除名、再改状态，被叫停的任务
 *    后到的进度不能把徽标改回「下载中」——改回去就是一个没有任务在跑、却永远转圈的徽标。
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

    /**
     * 在途任务。**必须是并发安全的**：`start` 从主线程（重组/点击）调，
     * 而 `finally { jobs.remove }` 跑在下载协程的后台线程上——用普通 HashMap
     * 会漏判"已在途"，同一条媒体被下两遍（2026-09-08 真机日志里实测到过：
     * 同一段 10MB 视频 `media_download_ok` 打了两次）。
     */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** 在途下载数（省电模式「后台保持连接」据此推迟断连）。 */
    val activeCount: Int get() = jobs.size

    /** `start` 的临界区：判"在不在途"与"起任务"必须是一步，否则两次调用会各起一个。 */
    private val startLock = Any()

    /** 本会话内已知失效的地址——不再回源（iOS `IMMediaExpiryRegistry` 的内存态那半）。 */
    private val expired = HashSet<String>()

    /** 某条媒体此刻的状态。本地已有 → Ready；已知失效 → Expired；否则看有没有在途记录。 */
    fun stateOf(url: String, isVideo: Boolean = false): DownloadState {
        if (url.isBlank()) return DownloadState(DownloadPhase.NotStarted)
        _states.value[url]?.let { if (it.phase != DownloadPhase.Ready) return it }
        if (cache.isReady(url, isVideo)) return DownloadState(DownloadPhase.Ready)
        if (url in expired) return DownloadState(DownloadPhase.Expired)
        // 走到这里，记录要么没有，要么是 Ready 而文件已经不在了（清了缓存 / 被系统清理）。
        // 后者照旧报 Ready 的话，localFile 给 null、Coil 拿不到图，气泡是一块空白而不是「未下载 ↓」。
        return DownloadState(DownloadPhase.NotStarted)
    }

    fun localFile(url: String, isVideo: Boolean = false) =
        cache.fileFor(url, isVideo).takeIf { it.isFile && it.length() > 0 }

    /** 已下载媒体占用的字节（「数据和存储 ▸ 存储用量」）。 */
    fun cachedBytes(): Long = cache.totalBytes()

    /** 开始 / 重试。已在途或已就绪时是 no-op（重复点不会下两遍）。 */
    fun start(url: String, isVideo: Boolean = false, expectedBytes: Long = 0) {
        if (url.isBlank() || url in expired) return
        if (cache.isReady(url, isVideo)) return
        synchronized(startLock) {
            if (jobs[url]?.isActive == true) return
            put(url, DownloadState(DownloadPhase.Downloading, 0, expectedBytes))
            // LAZY：**先登记、再开跑**。任务只在自己仍登记在册时才写状态（纪律 4），
            // 直接 launch 的话，秒下完的小文件可能赶在登记之前跑完，Ready 写不进去，徽标永远转圈。
            val job = scope.launch(start = CoroutineStart.LAZY) { run(url, isVideo, expectedBytes) }
            jobs[url] = job
            job.start()
        }
    }

    /**
     * 暂停：取消协程并删掉半截文件（**没有续传**，见类注释）。
     *
     * 与 [start] / [clearAll] 同一把锁：不加锁的话，除名之后、置 Paused 之前若有一次 `start` 插进来，
     * 会删掉新任务正在写的 `.part`，再把它的「下载中」盖成「已暂停」——徽标说停了，后台其实还在下。
     */
    fun pause(url: String, isVideo: Boolean = false) = synchronized(startLock) {
        jobs.remove(url)?.cancel()
        runCatching { cache.partFor(url, isVideo).delete() }
        put(url, DownloadState(DownloadPhase.Paused))
    }

    /**
     * 清掉全部已下载媒体（「数据和存储 ▸ 存储用量」，对齐 iOS `IMDataStorageViewController.clearCache`）。
     *
     * **必须经这里，不能直接 `cache.clear()`**：在途任务不先叫停，它下完会把文件改名回来；
     * [states] 不清，已经画出来的格子收不到一次发射、不会重算，仍显示成已下载。
     * 失效登记**不清**——那是服务端已经删掉的事实，清本机缓存改变不了它。
     *
     * @return 清掉的字节数。
     */
    fun clearAll(): Long = synchronized(startLock) {
        val cancelled = jobs.size
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        val freed = cache.totalBytes()
        cache.clear()
        _states.value = emptyMap()
        // 破坏性且不可撤销：不留痕就答不上「我的图片怎么全没了 / 怎么又重下了一遍」（iOS 同一行日志）
        log.i("media_cache_cleared", "bytes" to freed, "cancelled" to cancelled)
        freed
    }

    /**
     * **用 update 不用 `value = value + …`**：后者是读-改-写，
     * 多条下载并发上报进度时会互相覆盖（丢的是进度，表现为环卡在某个百分比不动）。
     */
    private fun put(url: String, s: DownloadState) {
        _states.update { it + (url to s) }
    }

    private suspend fun run(url: String, isVideo: Boolean, expectedBytes: Long) {
        val me = currentCoroutineContext()[Job]

        // 纪律 4：暂停 / 清缓存已经把本任务除名的话，它之后的任何状态都不算数
        fun report(s: DownloadState) {
            if (me != null && jobs[url] === me) put(url, s)
        }

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
                        report(DownloadState(DownloadPhase.Expired))
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
                                // 阻塞读不响应取消：不在这里查一次，暂停 / 清缓存之后流会照样一路读完
                                ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                received += n
                                report(DownloadState(DownloadPhase.Downloading, received, total))
                            }
                        }
                    }
                    // **下完才改名**：半截 .part 绝不能被当成已就绪
                    if (received <= 0) throw IOException("zero bytes")
                    ensureActive()
                    runCatching { dst.delete() }
                    if (!part.renameTo(dst)) throw IOException("rename failed")
                    report(DownloadState(DownloadPhase.Ready, received, total))
                    log.i("media_download_ok", "bytes" to received)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 暂停 / 清缓存走的就是这条：状态已由它们置好，别覆盖成 Failed
            runCatching { part.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { part.delete() }
            report(DownloadState(DownloadPhase.Failed))
            log.w("media_download_failed", "err" to (e.message ?: e::class.simpleName ?: "?"))
        } finally {
            // 只除名自己：暂停后立刻重下时，新任务已登记在同一个 url 上，不能被旧任务的 finally 顺手删掉
            if (me != null) jobs.remove(url, me)
        }
    }
}
