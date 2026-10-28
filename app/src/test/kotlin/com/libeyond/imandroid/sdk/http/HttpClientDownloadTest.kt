package com.libeyond.imandroid.sdk.http

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 停滞连接下取消下载：必须靠 Call.cancel() 立刻返回，而不是等 30s 读超时。 */
class HttpClientDownloadTest {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    private val accepted = CountDownLatch(1)
    private val sockets = mutableListOf<Socket>()

    @Before fun setUp() {
        IMLog.useSinksForTest()
        Thread {
            runCatching {
                val s = server.accept().also { sockets += it }
                s.getInputStream().read(ByteArray(4096)) // 吃掉请求
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
                    write(ByteArray(10) { 1 }) // 只发 10 字节，然后停滞
                    flush()
                }
                accepted.countDown()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() { sockets.forEach { runCatching { it.close() } }; server.close() }

    @Test fun `停滞连接下取消 远小于读超时内返回 且已写的半截可被调用方清理`() = runBlocking {
        val http = HttpClient("127.0.0.1:${server.localPort}")
        val sink = ByteArrayOutputStream()
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { http.download("/x.zip", sink) }
        assertTrue(accepted.await(3, TimeUnit.SECONDS))
        delay(200) // 让客户端进入阻塞读
        val t0 = System.nanoTime()
        withTimeout(5000) { job.cancelAndJoin() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("取消耗时 ${ms}ms，应远小于 30s 读超时", ms < 3000)
        assertTrue(job.isCancelled)
        assertEquals(10, sink.size()) // 只收到停滞前的半截
    }
}
