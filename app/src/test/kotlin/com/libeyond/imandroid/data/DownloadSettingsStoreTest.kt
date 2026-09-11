package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 自动下载策略的读、存、回滚与多端同步。重点是**时序**：乱序应答、离线保存、切账号——
 * 这几种情况下出错不会崩，只会让设置页停在一个不是服务端真值的状态，而且没人发现。
 */
class DownloadSettingsStoreTest {

    private val d = DownloadPolicy.defaults()
    private val off = d.copy(wifi = d.wifi.copy(enabled = false))
    private val low = d.copy(cellular = DownloadPolicy.applyTier(d.cellular, DownloadPolicy.Tier.Low))

    @Before
    fun quietLogs() = IMLog.useSinksForTest()

    /** 按调用顺序吐预设结果的假服务端。 */
    private class Fake {
        val fetches = ArrayDeque<suspend () -> Pair<Long, DownloadSettings>>()
        val puts = ArrayDeque<suspend (DownloadSettings) -> Pair<Long, DownloadSettings>>()
        var fetchCount = 0
        var putCount = 0

        fun store() = DownloadSettingsStore(
            fetch = { fetchCount++; fetches.removeFirst()() },
            put = { putCount++; puts.removeFirst()(it) },
            reset = { throw IOException("unused") },
        )
    }

    @Test
    fun `版本号：等于也采纳，小于才丢`() {
        assertTrue(DownloadSettingsSync.shouldApply(applied = 5, incoming = 5))
        assertTrue(DownloadSettingsSync.shouldApply(applied = DownloadSettingsSync.UNKNOWN, incoming = 0))
        assertFalse(DownloadSettingsSync.shouldApply(applied = 6, incoming = 5))
    }

    @Test
    fun `推送去重：采纳过的版本不重拉`() {
        assertFalse(DownloadSettingsSync.shouldRefetch(applied = 7, pushed = 7))
        assertTrue(DownloadSettingsSync.shouldRefetch(applied = 7, pushed = 8))
        assertTrue(DownloadSettingsSync.shouldRefetch(applied = DownloadSettingsSync.UNKNOWN, pushed = 0))
    }

    @Test
    fun `过期的应答不覆盖更新的值`() = runTest {
        val f = Fake()
        f.fetches += { 6L to off }
        f.fetches += { 5L to low }
        val store = f.store()
        store.refresh("t")
        store.refresh("t")
        assertEquals(VersionedSettings(6, off), store.state.value)
    }

    @Test
    fun `推送帧版本没变就不发请求`() = runTest {
        val f = Fake()
        f.fetches += { 3L to off }
        val store = f.store()
        store.refresh("t")
        store.onPushed(3)
        assertEquals(1, f.fetchCount)
        f.fetches += { 4L to low }
        store.onPushed(4)
        assertEquals(2, f.fetchCount)
        assertEquals(low, store.current)
    }

    @Test
    fun `保存成功采纳服务端规整后的值`() = runTest {
        val f = Fake()
        f.fetches += { 1L to d }
        // 端上误传了图片上限，服务端 normalized 会把它归 0
        val sent = d.copy(wifi = d.wifi.copy(enabled = false, image = d.wifi.image.copy(maxBytes = 5)))
        f.puts += { 2L to off }
        val store = f.store()
        store.refresh("t")
        assertTrue(store.save(sent))
        assertEquals(VersionedSettings(2, off), store.state.value)
    }

    @Test
    fun `保存失败且离线：退回改之前的值`() = runTest {
        val f = Fake()
        f.fetches += { 1L to d }
        f.puts += { throw IOException("offline") }
        f.fetches += { throw IOException("offline") }
        val store = f.store()
        store.refresh("t")
        assertFalse(store.save(off))
        // 重拉也失败了——只靠重拉回滚的话，这里会停在一个没存上的 off
        assertEquals(VersionedSettings(1, d), store.state.value)
    }

    @Test
    fun `前一次保存失败时已经又改过一次：不拿旧快照抹掉后一次`() = runTest {
        val f = Fake()
        f.fetches += { 1L to d }
        val firstPut = CompletableDeferred<Pair<Long, DownloadSettings>>()
        f.puts += { firstPut.await() }
        f.puts += { 2L to low }
        f.fetches += { throw IOException("offline") }
        val store = f.store()
        store.refresh("t")

        val first = launch { store.save(off) }
        runCurrent() // 第一次保存停在 PUT 上
        assertTrue(store.save(low))
        firstPut.completeExceptionally(IOException("offline"))
        first.join()

        assertEquals(VersionedSettings(2, low), store.state.value)
    }

    @Test
    fun `没改就不发 PUT`() = runTest {
        val f = Fake()
        f.fetches += { 1L to d }
        val store = f.store()
        store.refresh("t")
        assertTrue(store.save(d))
        assertEquals(0, f.putCount)
    }

    @Test
    fun `切账号后，上一个账号在途的应答作废`() = runTest {
        val f = Fake()
        val slow = CompletableDeferred<Pair<Long, DownloadSettings>>()
        f.fetches += { slow.await() }
        val store = f.store()

        val pending = launch { store.refresh("t") }
        runCurrent()
        store.forget()
        slow.complete(9L to off)
        pending.join()

        assertEquals(VersionedSettings(DownloadSettingsSync.UNKNOWN, d), store.state.value)
    }
}
