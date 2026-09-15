package com.libeyond.imandroid.sdk.ws

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * 服务端**先发关闭帧**再断开时，本端要立刻发现并走「重连 → 握手 401 → 回登录页」。
 *
 * 这正是「iOS 改密码 → Android 其它设备下线很慢」的现场（2026-09-15 用户报）：
 * 服务端 `Hub.KickSession` 走 `writePump` 发一帧 close 再关 TCP。OkHttp 收到 close 只回调
 * `onClosing`、读线程随即退出，**不会自己回 close**——不回的话 `onClosed` 永远不来，
 * 连接停在「看着已连接」的僵尸态，要等下一次 25s 应用层 ping 写失败才发现。
 *
 * 用一个手搓的最小 WS 服务端复现（不引 mockwebserver）：第 1 条连接握手成功后只发 close 帧并断开，
 * 第 2 条连接回 401。断言 [IMSocketManager.sessionEnded] 在几秒内给出 Revoked。
 */
class ServerCloseKickTest {

    private lateinit var server: ServerSocket
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        IMLog.useSinksForTest()
        server = ServerSocket(0)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { upgradeThenClose(it) }
                server.accept().use { reject401(it) }
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `服务端发关闭帧踢下线后几秒内回到登录页`() = runBlocking {
        val ws = IMSocketManager(scope, host = "127.0.0.1:${server.localPort}", tokenProvider = { "t" })
        ws.connect()
        // 修复前要等 25s 的 ping 才发现；重连退避首档是 1s，给 6s 足够且远小于 25s
        val reason = withTimeoutOrNull(6_000) { ws.sessionEnded.first() }
        ws.disconnect()
        assertEquals(SessionEndReason.Revoked, reason)
    }

    /** 握手 101，然后照 gorilla 服务端的踢人路径：一帧无负载的 close，随即关连接。 */
    private fun upgradeThenClose(s: Socket) {
        val key = readHeaders(s).firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }?.substringAfter(':')?.trim()
        } ?: return
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()),
        )
        val out = s.getOutputStream()
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
        )
        out.write(byteArrayOf(0x88.toByte(), 0x00)) // FIN + opcode 8（close），负载长度 0
        out.flush()
        Thread.sleep(200)
    }

    private fun reject401(s: Socket) {
        readHeaders(s)
        s.getOutputStream().apply {
            write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
    }

    private fun readHeaders(s: Socket): List<String> {
        val reader = s.getInputStream().bufferedReader()
        return generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
