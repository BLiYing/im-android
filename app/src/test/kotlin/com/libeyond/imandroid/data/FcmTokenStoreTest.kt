package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [FcmTokenSync] 的纯判据 + [FcmTokenStore] 的编排：没会话时不发请求只记待补报、同一枚不重发、
 * 会话就绪后补报、开关关闭时删令牌并忘记本地状态、退出登录只本地复位不调服务端。
 */
class FcmTokenStoreTest {

    @Before
    fun quietLogs() = IMLog.useSinksForTest()

    // ——— FcmTokenSync（纯函数）———

    @Test
    fun `没会话——一律 Defer`() {
        assertEquals(FcmTokenAction.Defer, FcmTokenSync.decide(hasSession = false, token = "t1", alreadyReported = null))
        assertEquals(FcmTokenAction.Defer, FcmTokenSync.decide(hasSession = false, token = "t1", alreadyReported = "t1"))
    }

    @Test
    fun `有会话且是新值——Put；和已上报的一样——Skip`() {
        assertEquals(FcmTokenAction.Put, FcmTokenSync.decide(hasSession = true, token = "t1", alreadyReported = null))
        assertEquals(FcmTokenAction.Put, FcmTokenSync.decide(hasSession = true, token = "t2", alreadyReported = "t1"))
        assertEquals(FcmTokenAction.Skip, FcmTokenSync.decide(hasSession = true, token = "t1", alreadyReported = "t1"))
    }

    // ——— FcmTokenStore（编排）———

    /** 按调用顺序吐预设结果的假服务端 + 本地偏好镜像。 */
    private class Fake(sessionReady: Boolean = true, enabled: Boolean = true) {
        var hasSession = sessionReady
        var enabled = enabled
        val puts = ArrayDeque<suspend (String) -> Unit>()
        val deletes = ArrayDeque<suspend () -> Unit>()
        var putCount = 0
        var deleteCount = 0

        fun store() = FcmTokenStore(
            hasSession = { hasSession },
            isEnabled = { enabled },
            persistEnabled = { enabled = it },
            put = { token -> putCount++; puts.removeFirst()(token) },
            delete = { deleteCount++; deletes.removeFirst()() },
        )
    }

    @Test
    fun `没会话——不发请求，记成待补报`() = runTest {
        val f = Fake(sessionReady = false)
        val store = f.store()
        store.reportToken("tok1")
        assertEquals(0, f.putCount)
        assertEquals("tok1", store.pendingToken.value)
        assertNull(store.reportedToken.value)
    }

    @Test
    fun `有会话——直接上报，reportedToken 更新，pending 清空`() = runTest {
        val f = Fake(sessionReady = true)
        f.puts += { Unit }
        val store = f.store()
        store.reportToken("tok1")
        assertEquals(1, f.putCount)
        assertEquals("tok1", store.reportedToken.value)
        assertNull(store.pendingToken.value)
    }

    @Test
    fun `同一枚 token 不重复上报`() = runTest {
        val f = Fake(sessionReady = true)
        f.puts += { Unit }
        val store = f.store()
        store.reportToken("tok1")
        store.reportToken("tok1")
        assertEquals(1, f.putCount)
    }

    @Test
    fun `onSessionReady 补报之前攒下的 pending token`() = runTest {
        val f = Fake(sessionReady = false)
        val store = f.store()
        store.reportToken("tok1")
        assertEquals(0, f.putCount)

        f.hasSession = true
        f.puts += { Unit }
        store.onSessionReady()
        assertEquals(1, f.putCount)
        assertEquals("tok1", store.reportedToken.value)
        assertNull(store.pendingToken.value)
    }

    @Test
    fun `上报失败——记回 pending，不算已上报`() = runTest {
        val f = Fake(sessionReady = true)
        f.puts += { throw IOException("offline") }
        val store = f.store()
        store.reportToken("tok1")
        assertNull(store.reportedToken.value)
        assertEquals("tok1", store.pendingToken.value)
    }

    @Test
    fun `开关关着——reportToken 整个短路，不记 pending 也不发请求`() = runTest {
        val f = Fake(sessionReady = true, enabled = false)
        val store = f.store()
        store.reportToken("tok1")
        assertEquals(0, f.putCount)
        assertNull(store.pendingToken.value)
    }

    @Test
    fun `setEnabled(true)——持久化偏好，取到 token 就报`() = runTest {
        val f = Fake(sessionReady = true, enabled = false)
        f.puts += { Unit }
        val store = f.store()
        store.setEnabled(true) { "tok1" }
        assertTrue(f.enabled)
        assertEquals("tok1", store.reportedToken.value)
    }

    @Test
    fun `setEnabled(false)——删服务端令牌，本地也忘掉已上报状态`() = runTest {
        val f = Fake(sessionReady = true, enabled = true)
        f.puts += { Unit }
        val store = f.store()
        store.reportToken("tok1")
        assertEquals("tok1", store.reportedToken.value)

        f.deletes += { Unit }
        store.setEnabled(false) { "tok1" } // fetchCurrentToken 在关的分支不会被调用
        assertEquals(1, f.deleteCount)
        assertEquals(false, f.enabled)
        assertNull(store.reportedToken.value)
        assertNull(store.pendingToken.value)
    }

    @Test
    fun `forget 只复位本地状态，不调服务端 delete`() = runTest {
        val f = Fake(sessionReady = true)
        f.puts += { Unit }
        val store = f.store()
        store.reportToken("tok1")
        assertEquals("tok1", store.reportedToken.value)

        store.forget()
        assertNull(store.reportedToken.value)
        assertNull(store.pendingToken.value)
        assertEquals(0, f.deleteCount)
    }
}
