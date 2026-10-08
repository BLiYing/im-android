package com.libeyond.imandroid.sdk.ws

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 握手 401 先续期、续期被拒才结束会话；[IMSocketManager.connect] 之前唤醒不连。
 *
 * 2026-10-08 Pixel 实测：登录满 24h 后冷启动，网络唤醒抢在 restore 前拿过期 token 握手 401，
 * 被当成「已被吊销」清了本机凭据——服务端会话与 FCM 令牌都还在，登录页上照样弹来电。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IMSocketUnauthorizedTest {

    private class FakeWs(val req: Request) : WebSocket {
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private class Rig(scope: TestScope, refresh: (suspend () -> TokenRefresh)?) {
        var token = "old"
        val sockets = mutableListOf<FakeWs>()
        val listeners = mutableListOf<WebSocketListener>()
        val ended = mutableListOf<SessionEndReason>()
        val m = IMSocketManager(scope.backgroundScope, "h", false, { token }, refresh) { req, l ->
            FakeWs(req).also { sockets += it; listeners += l }
        }

        init {
            scope.backgroundScope.launchCollect(m, ended)
        }

        fun reject(i: Int, code: Int = 401) {
            val resp = Response.Builder().request(sockets[i].req).protocol(Protocol.HTTP_1_1)
                .code(code).message("x").build()
            listeners[i].onFailure(sockets[i], IOException("handshake"), resp)
        }

        fun open(i: Int) {
            val resp = Response.Builder().request(sockets[i].req).protocol(Protocol.HTTP_1_1)
                .code(101).message("x").build()
            listeners[i].onOpen(sockets[i], resp)
        }

        fun authOf(i: Int) = sockets[i].req.header("Authorization")
    }

    @Before
    fun setUp() = IMLog.useSinksForTest()

    @Test
    fun `connect 之前的唤醒不连`() = runTest {
        val r = Rig(this) { TokenRefresh.Refreshed }
        r.m.wake("network_available")
        runCurrent()
        assertEquals(0, r.sockets.size)
        r.m.connect()
        assertEquals(1, r.sockets.size)
    }

    @Test
    fun `token 过期的 401：续上就用新 token 立刻重连，不结束会话`() = runTest {
        val r = Rig(this) { TokenRefresh.Refreshed }
        r.m.connect()
        r.token = "fresh" // 续期把新 token 落盘
        r.reject(0)
        runCurrent()
        assertEquals(2, r.sockets.size)
        assertEquals("Bearer fresh", r.authOf(1))
        r.open(1)
        assertEquals(ConnState.Connected, r.m.state.value)
        assertEquals(emptyList<SessionEndReason>(), r.ended)
    }

    @Test
    fun `续期后的新 token 还 401：按吊销结束，不再续`() = runTest {
        var calls = 0
        val r = Rig(this) { calls++; TokenRefresh.Refreshed }
        r.m.connect()
        r.reject(0)
        runCurrent()
        r.reject(1)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf(SessionEndReason.Revoked), r.ended)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("结束后不许再连", 2, r.sockets.size)
    }

    @Test
    fun `连上之后再遇到 401 还能再续一次`() = runTest {
        var calls = 0
        val r = Rig(this) { calls++; TokenRefresh.Refreshed }
        r.m.connect()
        r.reject(0); runCurrent()
        r.open(1)
        r.listeners[1].onClosed(r.sockets[1], 1000, "")
        advanceTimeBy(1_000); runCurrent()
        r.reject(2); runCurrent()
        assertEquals(2, calls)
        assertEquals(emptyList<SessionEndReason>(), r.ended)
    }

    @Test
    fun `续期被拒：按吊销结束会话`() = runTest {
        val r = Rig(this) { TokenRefresh.Rejected }
        r.m.connect()
        r.reject(0); runCurrent()
        assertEquals(listOf(SessionEndReason.Revoked), r.ended)
    }

    @Test
    fun `续期凭据到寿：按过期结束会话`() = runTest {
        val r = Rig(this) { TokenRefresh.Expired }
        r.m.connect()
        r.reject(0); runCurrent()
        assertEquals(listOf(SessionEndReason.Expired), r.ended)
    }

    @Test
    fun `续期接口连不上：不结束会话，退避重连`() = runTest {
        val r = Rig(this) { TokenRefresh.Unreachable }
        r.m.connect()
        r.reject(0); runCurrent()
        assertEquals(emptyList<SessionEndReason>(), r.ended)
        assertEquals(1, r.sockets.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, r.sockets.size)
    }

    @Test
    fun `续期途中退出登录：回来的结果作废`() = runTest {
        val gate = CompletableDeferred<TokenRefresh>()
        val r = Rig(this) { gate.await() }
        r.m.connect()
        r.reject(0); runCurrent()
        r.m.disconnect()
        gate.complete(TokenRefresh.Refreshed); runCurrent()
        assertEquals(1, r.sockets.size)
        assertEquals(emptyList<SessionEndReason>(), r.ended)
    }

    @Test
    fun `续期途中唤醒不另开连接`() = runTest {
        val gate = CompletableDeferred<TokenRefresh>()
        val r = Rig(this) { gate.await() }
        r.m.connect()
        r.reject(0); runCurrent()
        r.m.wake("foreground")
        assertEquals(1, r.sockets.size)
        gate.complete(TokenRefresh.Refreshed); runCurrent()
        assertEquals(2, r.sockets.size)
    }

    @Test
    fun `续期发现账号被封：按封号结束`() = runTest {
        val r = Rig(this) { TokenRefresh.Banned }
        r.m.connect()
        r.reject(0); runCurrent()
        assertEquals(listOf(SessionEndReason.Banned), r.ended)
    }

    /** /code-review 2026-10-08：续上后新连接因断网失败、用户退出又登录——第一次 401 不能直接当被踢。 */
    @Test
    fun `续过之后退出再登录，第一次 401 还能续`() = runTest {
        var calls = 0
        val r = Rig(this) { calls++; TokenRefresh.Refreshed }
        r.m.connect()
        r.reject(0); runCurrent()
        r.listeners[1].onFailure(r.sockets[1], IOException("net"), null)
        r.m.disconnect()
        r.m.connect()
        r.reject(2); runCurrent()
        assertEquals(2, calls)
        assertEquals(emptyList<SessionEndReason>(), r.ended)
    }

    @Test
    fun `续上后新连接断网、退避重连再 401：还能续`() = runTest {
        var calls = 0
        val r = Rig(this) { calls++; TokenRefresh.Refreshed }
        r.m.connect()
        r.reject(0); runCurrent()
        r.listeners[1].onFailure(r.sockets[1], IOException("net"), null)
        advanceTimeBy(1_000); runCurrent()
        r.reject(2); runCurrent()
        assertEquals(2, calls)
        assertEquals(emptyList<SessionEndReason>(), r.ended)
    }

    /**
     * 旧连接迟到的 401 不动新连接。注意：这条走的是 onFailure 开头的过期检查；它与 onUnauthorized 拿锁之间
     * 被另一线程插入的那种交错（锁内复查代数要挡的）单线程测不出来，见 onUnauthorized 注释。
     */
    @Test
    fun `旧连接迟到的 401 不动新连接`() = runTest {
        val r = Rig(this) { TokenRefresh.Refreshed }
        r.m.connect()
        // 握手失败已把状态置 Idle，401 处理拿锁之前唤醒先开了新连接（第 2 条）
        r.listeners[0].onFailure(r.sockets[0], IOException("net"), null)
        r.m.wake("network_available")
        assertEquals(2, r.sockets.size)
        r.reject(0); runCurrent() // 旧连接迟到的 401：代数已变，丢弃
        assertEquals(2, r.sockets.size)
        assertEquals(ConnState.Connecting, r.m.state.value)
        assertEquals(emptyList<SessionEndReason>(), r.ended)
    }

    @Test
    fun `没接续期能力时 401 直接按吊销（旧行为）`() = runTest {
        val r = Rig(this, null)
        r.m.connect()
        r.reject(0); runCurrent()
        assertEquals(listOf(SessionEndReason.Revoked), r.ended)
    }

    @Test
    fun `403 仍按封号结束`() = runTest {
        val r = Rig(this) { TokenRefresh.Refreshed }
        r.m.connect()
        r.reject(0, 403); runCurrent()
        assertEquals(listOf(SessionEndReason.Banned), r.ended)
        assertNull(r.listeners.getOrNull(1))
    }
}

private fun CoroutineScope.launchCollect(m: IMSocketManager, into: MutableList<SessionEndReason>) {
    launch(Dispatchers.Unconfined) { m.sessionEnded.collect { into += it } }
}
