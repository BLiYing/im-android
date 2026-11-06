package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 退出登录吊销的持久化重试（LOGOUT_REVOKE_RETRY_DESIGN）。与 iOS `IMPendingLogoutQueueTests` 同一组场景。 */
class PendingLogoutQueueTest {

    @Before fun quietLogs() = IMLog.useSinksForTest()

    private val url = "https://im.example.com/api/v1/logout/refresh"
    private var stored: String? = null
    private var nowMs = 1_000_000L
    private val sent = mutableListOf<PendingLogoutEntry>()
    private var failure: ApiException? = null
    private var failOnlyToken: String? = null

    private fun queue() = PendingLogoutQueue(
        read = { stored },
        write = { stored = it },
        send = { entry ->
            sent += entry
            val f = failure
            if (f != null && (failOnlyToken == null || failOnlyToken == entry.token)) throw f
        },
        now = { nowMs },
    )

    private fun transportError() = ApiException(ApiException.TRANSPORT, "offline", "req")
    private fun bizError(code: Int) = ApiException(code, "x", "req")

    @Test
    fun `重试判定`() {
        assertTrue(PendingLogoutQueue.shouldRetry(transportError()))
        assertTrue(PendingLogoutQueue.shouldRetry(bizError(ErrCode.RATE_LIMITED)))
        assertTrue(PendingLogoutQueue.shouldRetry(bizError(ErrCode.INTERNAL)))
        assertFalse(PendingLogoutQueue.shouldRetry(bizError(ErrCode.PARAM_INVALID)))
        assertFalse(PendingLogoutQueue.shouldRetry(bizError(ErrCode.TOKEN_INVALID)))
    }

    @Test
    fun `成功后不留凭据`() = runTest {
        val q = queue()
        q.enqueue("t1", url)
        assertNotNull("发送前必须已落盘，发送途中进程可能被杀", stored)
        assertFalse(q.flush())
        assertEquals(listOf("t1"), sent.map { it.token })
        assertEquals(url, sent.single().url)
        assertNull(stored)
    }

    @Test
    fun `网络失败保留凭据，之后重试成功`() = runTest {
        failure = transportError()
        val q = queue()
        q.enqueue("t1", url)
        assertTrue(q.flush())
        assertEquals(1, q.pendingCount())

        failure = null
        assertFalse(q.flush())
        assertEquals(0, q.pendingCount())
        assertNull(stored)
    }

    @Test
    fun `服务端明确拒绝就丢弃`() = runTest {
        failure = bizError(ErrCode.PARAM_INVALID)
        val q = queue()
        q.enqueue("t1", url)
        assertFalse(q.flush())
        assertNull(stored)
    }

    /** 关键场景：断网退出后杀进程，下次启动（可能已换账号）仍能补吊销，且发往当初的服务器。 */
    @Test
    fun `凭据跨进程保留并发往原服务器`() = runTest {
        failure = transportError()
        queue().apply { enqueue("t1", url); flush() }
        sent.clear()

        failure = null
        val restarted = queue()
        assertEquals(1, restarted.pendingCount())
        assertFalse(restarted.flush())
        assertEquals(url, sent.single().url)
        assertEquals(0, restarted.pendingCount())
    }

    @Test
    fun `一条失败不影响其它条`() = runTest {
        failure = transportError()
        failOnlyToken = "down"
        val q = queue()
        q.enqueue("down", url)
        q.enqueue("ok", url)
        assertTrue(q.flush())
        assertEquals(listOf("down", "ok"), sent.map { it.token })
        assertEquals(1, q.pendingCount())
    }

    @Test
    fun `超过寿命的凭据不发送直接丢弃`() = runTest {
        val q = queue()
        q.enqueue("t1", url)
        nowMs += PendingLogoutQueue.MAX_AGE_MS + 1
        assertFalse(q.flush())
        assertTrue(sent.isEmpty())
        assertNull(stored)
    }

    @Test
    fun `队列有上限且丢最旧的`() = runTest {
        val q = queue()
        repeat(20) { q.enqueue("t$it", url) }
        assertEquals(PendingLogoutQueue.MAX_ENTRIES, q.pendingCount())
        q.flush()
        assertEquals("t4", sent.first().token)
        assertEquals("t19", sent.last().token)
    }

    @Test
    fun `同一枚凭据不重复`() = runTest {
        val q = queue()
        q.enqueue("t1", url)
        q.enqueue("t1", url)
        assertEquals(1, q.pendingCount())
    }

    /** OkHttp 对非法 URL 抛 IllegalArgumentException：不能往外抛（会让 App 崩溃），也不能挡住别的条目。 */
    @Test
    fun `地址非法时丢弃这条并继续`() = runTest {
        val q = PendingLogoutQueue(
            read = { stored },
            write = { stored = it },
            send = { entry ->
                sent += entry
                if (entry.token == "bad") throw IllegalArgumentException("bad url")
            },
            now = { nowMs },
        )
        q.enqueue("bad", "not a url")
        q.enqueue("ok", url)
        assertFalse(q.flush())
        assertEquals(listOf("bad", "ok"), sent.map { it.token })
        assertNull(stored)
    }

    /** 存储内容损坏不能让退出流程崩，也不能挡住后面的新条目。 */
    @Test
    fun `存储损坏时按空处理`() = runTest {
        stored = "not json"
        val q = queue()
        assertEquals(0, q.pendingCount())
        q.enqueue("t1", url)
        assertFalse(q.flush())
        assertEquals(listOf("t1"), sent.map { it.token })
    }
}
