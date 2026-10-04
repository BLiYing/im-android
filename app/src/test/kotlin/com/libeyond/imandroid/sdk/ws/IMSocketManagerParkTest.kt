package com.libeyond.imandroid.sdk.ws

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class IMSocketManagerParkTest {

    private class FakeWs(val req: Request) : WebSocket {
        var cancelled = false
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }

    private class Rig(scope: kotlinx.coroutines.CoroutineScope) {
        val sockets = mutableListOf<FakeWs>()
        val listeners = mutableListOf<WebSocketListener>()
        val m = IMSocketManager(scope, "h", false, { "tok" }) { req, l ->
            FakeWs(req).also { sockets += it; listeners += l }
        }
    }

    @Test
    fun pendingBackoffReconnectDoesNotOpenAfterPark() = runTest {
        val r = Rig(backgroundScope)
        r.m.connect()
        r.listeners[0].onFailure(r.sockets[0], IOException("net"), null) // 排上 1s 退避重连
        r.m.park()
        advanceTimeBy(5_000); runCurrent()
        assertEquals("park 之后不许再开连接", 1, r.sockets.size)
        assertEquals(ConnState.Idle, r.m.state.value)
    }

    @Test
    fun unparkReconnectsImmediatelyWithoutBackoff() = runTest {
        val r = Rig(backgroundScope)
        r.m.connect()
        r.m.park() // 握手中停靠：在途那条被 cancel
        assertEquals(true, r.sockets[0].cancelled)
        r.listeners[0].onFailure(r.sockets[0], IOException("cancelled"), null) // 迟到回调：作废，不排退避
        r.m.unpark()
        assertEquals(2, r.sockets.size) // 立即开，没等 1s
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, r.sockets.size)
    }

    @Test
    fun staleOnOpenAfterParkDoesNotMarkConnected() = runTest {
        val r = Rig(backgroundScope)
        r.m.connect()
        r.m.park()
        r.listeners[0].onOpen(r.sockets[0], okhttp3.Response.Builder().request(r.sockets[0].req)
            .protocol(okhttp3.Protocol.HTTP_1_1).code(101).message("x").build())
        assertEquals(ConnState.Idle, r.m.state.value)
    }
}
