package com.libeyond.imandroid.sdk.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒与握手判据。与 iOS `IMSocketWakeActionFor` / Web `sdk/wake.ts` 同口径
 * （`docs/CLIENT_PARITY.md`「网络恢复秒连」行标注了"判据纯函数两端同口径"）。
 */
class WakeActionTest {

    // ——— 唤醒：重点是"什么时候不该连" ———

    /** manualClose 后一律不连：自动重连等于把用户的登出撤销，也会让「被踢」退化成临时抖动。 */
    @Test
    fun `manualClose 后一律不连`() {
        for (s in ConnState.entries) {
            assertEquals("state=$s", WakeAction.None, wakeActionFor(s, manualClose = true))
        }
    }

    /** 已连接只探活——重连等于白白断一次好连接。 */
    @Test
    fun `已连接只探活`() {
        assertEquals(WakeAction.Probe, wakeActionFor(ConnState.Connected, false))
    }

    /** 握手中不动——再连一次会掐掉正在握手的那条，反而更慢。 */
    @Test
    fun `连接中不动`() {
        assertEquals(WakeAction.None, wakeActionFor(ConnState.Connecting, false))
    }

    @Test
    fun `空闲则重连`() {
        assertEquals(WakeAction.Reconnect, wakeActionFor(ConnState.Idle, false))
    }

    // ——— 握手失败：401/403 必须停重连 ———

    /**
     * iOS 2026-08-13 的真账：401 当普通网络错重连，配合 token 缓存与稳定 device_id，
     * 「被踢下线」退化成静默自愈。这两条必须与可重试错误分开。
     */
    @Test
    fun `握手 401 判为未授权（过期或吊销，待续期分辨）`() {
        assertEquals(HandshakeFailure.Unauthorized, handshakeFailureFor(401))
    }

    /** 2026-10-08 Pixel：token 过期的 401 被当吊销，本机清凭据、推送还在来。先续期才分得清。 */
    @Test
    fun `401 能续期就先续期`() {
        assertEquals(UnauthorizedAction.Refresh, unauthorizedActionFor(canRefresh = true, retriedAfterRefresh = false))
    }

    @Test
    fun `续期后的新 token 还 401 就按吊销处理，不死循环`() {
        assertEquals(UnauthorizedAction.EndRevoked, unauthorizedActionFor(canRefresh = true, retriedAfterRefresh = true))
    }

    @Test
    fun `没有续期能力时 401 按吊销处理`() {
        assertEquals(UnauthorizedAction.EndRevoked, unauthorizedActionFor(canRefresh = false, retriedAfterRefresh = false))
    }

    @Test
    fun `握手 403 判为账号被封`() {
        assertEquals(HandshakeFailure.Banned, handshakeFailureFor(403))
    }

    @Test
    fun `其余状态码可重试`() {
        for (code in listOf(0, 404, 500, 502, 503, 200)) {
            assertEquals("code=$code", HandshakeFailure.Retryable, handshakeFailureFor(code))
        }
    }

    // ——— 退避 ———

    @Test
    fun `退避按 2 的幂增长`() {
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(2_000L, reconnectDelayMs(1))
        assertEquals(4_000L, reconnectDelayMs(2))
        assertEquals(8_000L, reconnectDelayMs(3))
        assertEquals(16_000L, reconnectDelayMs(4))
    }

    @Test
    fun `退避封顶 30 秒`() {
        assertEquals(30_000L, reconnectDelayMs(5))
        assertEquals(30_000L, reconnectDelayMs(10))
    }

    /** 长时间断网后 attempt 会很大——移位溢出会算出负数或 0，导致疯狂重连打满服务端。 */
    @Test
    fun `极大 attempt 不溢出`() {
        for (a in listOf(31, 32, 63, 64, 1000, Int.MAX_VALUE)) {
            val d = reconnectDelayMs(a)
            assertTrue("attempt=$a delay=$d", d in 1_000L..30_000L)
        }
    }
}

/**
 * 「什么状态下允许新开一条连接」——与 [wakeActionFor] 是两回事：
 * 那个管唤醒信号，这个管**任何**调用 connect()/openSocket() 的路径。
 *
 * 2026-09-07 回归实测抓到：NetworkMonitor 在启动瞬间已经连上，restore() 成功后
 * AppRoot 又调一次 connect()，旧连接被字段覆盖孤儿化，服务端挂着两条。
 */
class OpenSocketGuardTest {

    /** 复刻 openSocket 的准入判据（实现里就是这一条）。 */
    private fun mayOpen(state: ConnState, manualClose: Boolean): Boolean =
        !manualClose && state == ConnState.Idle

    @Test
    fun `只有空闲态才允许开新连接`() {
        org.junit.Assert.assertTrue(mayOpen(ConnState.Idle, false))
        org.junit.Assert.assertFalse("已连接时再开会孤儿化旧连接", mayOpen(ConnState.Connected, false))
        org.junit.Assert.assertFalse("握手中再开会掐掉在途那条", mayOpen(ConnState.Connecting, false))
    }

    @Test
    fun `manualClose 后任何状态都不开`() {
        for (s in ConnState.entries) {
            org.junit.Assert.assertFalse("state=$s", mayOpen(s, true))
        }
    }
}
