package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载器与「清除缓存 / 暂停」的交互。起一个本机 HTTP 服务——
 * 这几条都是**真实时序**问题（在途任务、阻塞读、改名），假网络层测不出来。
 */
class MediaDownloaderTest {

    private lateinit var server: TinyHttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 慢速端点实际写出去的块数；客户端中途断开后，服务端写不下去就会停在半路。 */
    private val slowChunksSent = AtomicInteger(0)
    private val slowDone = AtomicBoolean(false)

    @Before
    fun setUp() {
        IMLog.useSinksForTest()
        server = TinyHttpServer { path, out ->
            when (path) {
                "/uploads/small.jpg" -> {
                    out.head(SMALL_BYTES.toLong())
                    out.write(ByteArray(SMALL_BYTES) { 7 })
                }
                "/uploads/slow.bin" -> try {
                    out.head(SLOW_CHUNKS.toLong() * CHUNK)
                    repeat(SLOW_CHUNKS) {
                        out.write(ByteArray(CHUNK))
                        out.flush()
                        slowChunksSent.incrementAndGet()
                        Thread.sleep(15)
                    }
                } catch (_: IOException) {
                    // 客户端断开——正是要测的
                } finally {
                    slowDone.set(true)
                }
                // 先给一块、再长时间不给：客户端必然停在阻塞读上，暂停就落在「读返回之前」
                "/uploads/stall.bin" -> {
                    out.head(STALL_CHUNKS.toLong() * CHUNK)
                    repeat(STALL_CHUNKS) {
                        out.write(ByteArray(CHUNK))
                        out.flush()
                        Thread.sleep(600)
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun tmp() = File(System.getProperty("java.io.tmpdir"), "md-" + System.nanoTime())

    private fun downloader(cache: MediaCache) = MediaDownloader(
        scope = scope,
        cache = cache,
        absolute = { "http://127.0.0.1:${server.port}$it" },
        tokenProvider = { null },
    )

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private fun MediaDownloader.receiving(url: String) =
        stateOf(url).let { it.phase == DownloadPhase.Downloading && it.received > 0 }

    @Test
    fun `下完的文件被外部删掉后退回未下载，不再报就绪`() {
        val cache = MediaCache(tmp())
        val d = downloader(cache)
        val url = "/uploads/small.jpg"
        d.start(url)
        assertTrue(waitUntil { d.stateOf(url).phase == DownloadPhase.Ready })

        cache.fileFor(url).delete() // 系统清理 / 在文件管理器里删掉
        assertEquals(DownloadPhase.NotStarted, d.stateOf(url).phase)
        assertNull(d.localFile(url))
    }

    @Test
    fun `清缓存：文件与状态一起清空，界面收得到一次变化`() {
        val d = downloader(MediaCache(tmp()))
        val url = "/uploads/small.jpg"
        d.start(url)
        assertTrue(waitUntil { d.stateOf(url).phase == DownloadPhase.Ready })

        assertEquals(SMALL_BYTES.toLong(), d.clearAll())
        assertTrue(d.states.value.isEmpty())
        assertEquals(0L, d.cachedBytes())
        assertEquals(DownloadPhase.NotStarted, d.stateOf(url).phase)
    }

    @Test
    fun `下载中清缓存：任务真的停下，不会读完整个流也不留文件`() {
        val cache = MediaCache(tmp())
        val d = downloader(cache)
        val url = "/uploads/slow.bin"
        d.start(url)
        assertTrue(waitUntil { d.receiving(url) })

        d.clearAll()
        assertTrue("服务端写完或被断开", waitUntil(10_000) { slowDone.get() })

        assertTrue(
            "客户端应中途断开，服务端却写完了（${slowChunksSent.get()}/$SLOW_CHUNKS 块）",
            slowChunksSent.get() < SLOW_CHUNKS,
        )
        assertEquals(DownloadPhase.NotStarted, d.stateOf(url).phase)
        assertFalse(cache.fileFor(url).exists())
        assertFalse(cache.partFor(url).exists())
    }

    @Test
    fun `暂停后停在暂停，被叫停的任务读回来的数据不会把它改回下载中`() {
        val d = downloader(MediaCache(tmp()))
        val url = "/uploads/stall.bin"
        d.start(url)
        assertTrue(waitUntil { d.receiving(url) })

        d.pause(url)
        Thread.sleep(900) // 跨过服务端下一块到达的时刻
        assertEquals(DownloadPhase.Paused, d.stateOf(url).phase)
    }

    private companion object {
        const val SMALL_BYTES = 2048
        const val CHUNK = 64 * 1024

        /** 200 × 64KB ≈ 12.8MB：远大于本机 socket 缓冲，客户端不读了服务端必然写不完。 */
        const val SLOW_CHUNKS = 200
        const val STALL_CHUNKS = 5
    }
}

/**
 * 最小 HTTP/1.1 服务（只认请求行里的路径，一连接一请求）。
 * 单测的编译类路径是 android.jar，JDK 的 `com.sun.net.httpserver` 不在上面，也没引 MockWebServer。
 */
private class TinyHttpServer(private val handle: (path: String, out: OutputStream) -> Unit) : AutoCloseable {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()

    val port: Int get() = socket.localPort

    init {
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute {
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@use
                        while (reader.readLine()?.isNotEmpty() == true) Unit // 跳过请求头
                        val path = requestLine.split(" ").getOrElse(1) { "/" }
                        runCatching { handle(path, c.getOutputStream()) }
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        pool.shutdownNow()
    }
}

private fun OutputStream.head(length: Long) {
    write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
    flush()
}
