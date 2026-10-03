package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.UserCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileCacheTest {
    /** 推进虚拟时间让合批窗口走完（`advanceUntilIdle` 不会推进后台作用域里的 `delay`）。 */
    private suspend fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun card(id: String, nick: String = "n$id") = UserCard(userId = id, nickname = nick)

    private class Harness(val scope: TestScope, var failing: Boolean = false) {
        var clock = 0L
        val calls = mutableListOf<List<String>>()
        val missing = mutableSetOf<String>()
        val cache = UserProfileCache(
            scope = scope.backgroundScope,
            fetch = { ids ->
                calls += ids
                if (failing) error("net")
                ProfileBatch(ids.filter { it !in missing }.map { UserCard(userId = it, nickname = "n$it") }, ids.filter { it in missing })
            },
            now = { clock },
        )
    }

    @Test fun `短窗口内的缺失合成一批，重复 uid 只发一次`() = runTest {
        val h = Harness(this)
        h.cache.request("a"); h.cache.request("b"); h.cache.request("a")
        settle()
        assertEquals(listOf(listOf("a", "b")), h.calls)
        assertEquals("na", h.cache.peek("a")?.nickname)
    }

    @Test fun `命中缓存不再请求`() = runTest {
        val h = Harness(this)
        h.cache.ingest(listOf(card("a")))
        h.cache.request("a")
        settle()
        assertEquals(emptyList<List<String>>(), h.calls)
    }

    @Test fun `超过 100 个分批发`() = runTest {
        val h = Harness(this)
        (1..250).forEach { h.cache.request("u$it") }
        settle()
        assertEquals(listOf(100, 100, 50), h.calls.map { it.size })
    }

    @Test fun `missing 进负缓存，十分钟内不再问，期满可重试`() = runTest {
        val h = Harness(this).also { it.missing += "ghost" }
        h.cache.request("ghost"); settle()
        assertEquals(1, h.calls.size)
        h.clock += 1_000
        h.cache.request("ghost"); settle()
        assertEquals("负缓存期内不重发", 1, h.calls.size)
        h.clock += UserProfileCache.MISSING_TTL_MS
        h.cache.request("ghost"); settle()
        assertEquals("期满重试", 2, h.calls.size)
    }

    @Test fun `失败后退避五秒：期间不发也不排，之后恢复；失败不进负缓存`() = runTest {
        val h = Harness(this, failing = true)
        h.cache.request("a"); settle()
        assertEquals(1, h.calls.size)
        h.clock += 1_000
        h.cache.request("b"); settle()
        assertEquals("退避期不发", 1, h.calls.size)
        h.failing = false
        h.clock += UserProfileCache.FAILURE_BACKOFF_MS
        h.cache.request("a"); settle()
        assertEquals("退避过后恢复", 2, h.calls.size)
        assertNotNull(h.cache.peek("a"))
    }

    @Test fun `失败退避后同一个 uid 再被问要重新排上，不会永远卡在 pending`() = runTest {
        val h = Harness(this, failing = true)
        h.cache.request("a"); settle()
        assertEquals(1, h.calls.size)
        h.failing = false
        h.clock += UserProfileCache.FAILURE_BACKOFF_MS
        h.cache.request("a"); settle() // a 还留在 pending 里
        assertEquals(2, h.calls.size)
        assertNotNull(h.cache.peek("a"))
    }

    @Test fun `clear 之后才回来的在途结果被丢弃，不串号`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cache = UserProfileCache(
            scope = backgroundScope,
            fetch = { ids -> gate.await(); ProfileBatch(ids.map { UserCard(userId = it, nickname = "A账号") }, emptyList()) },
        )
        cache.request("x"); advanceTimeBy(100); runCurrent() // 请求在途
        cache.clear()
        gate.complete(Unit); settle()
        assertNull(cache.peek("x"))
    }

    @Test fun `超过 2000 条先进先出淘汰`() = runTest {
        val h = Harness(this)
        h.cache.ingest((1..2001).map { card("u$it") })
        assertNull(h.cache.peek("u1"))
        assertNotNull(h.cache.peek("u2001"))
    }

    @Test fun `清空后重新来过`() = runTest {
        val h = Harness(this)
        h.cache.ingest(listOf(card("a"))); h.cache.clear()
        assertNull(h.cache.peek("a"))
    }
}
