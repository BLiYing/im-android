package com.libeyond.imandroid.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ExportDownloaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discarded = CopyOnWriteArrayList<String>()
    private val gate = CompletableDeferred<Unit>()

    @After fun tearDown() { scope.cancel() }

    private fun downloader(run: suspend (String, String?) -> Unit) =
        ExportDownloader(scope, { t, j, _ -> run(t, j) }, { discarded += it })

    private suspend fun await(d: ExportDownloader, want: ExportDownloader.State) =
        withTimeout(3000) { while (d.state.value != want) delay(5) }

    @Test fun `成功 进入 Succeeded 且不删文件`() = runBlocking {
        val d = downloader { _, _ -> }
        assertTrue(d.start("u1", "j1"))
        await(d, ExportDownloader.State.Succeeded)
        assertTrue(discarded.isEmpty())
        d.consumeResult()
        assertEquals(ExportDownloader.State.Idle, d.state.value)
    }

    @Test fun `失败 进入 Failed 并清半截文件`() = runBlocking {
        val d = downloader { _, _ -> throw IOException("boom") }
        d.start("u1", null)
        await(d, ExportDownloader.State.Failed)
        assertEquals(listOf("u1"), discarded.toList())
    }

    @Test fun `进行中不重复启动 且不依赖调用方协程`() = runBlocking {
        val d = downloader { _, _ -> gate.await() }
        assertTrue(d.start("u1", "j1"))
        assertEquals(ExportDownloader.State.Running, d.state.value)
        assertFalse(d.start("u2", "j1"))
        gate.complete(Unit)
        await(d, ExportDownloader.State.Succeeded)
        assertTrue(discarded.isEmpty())
    }

    @Test fun `取消 回 Idle 清半截文件 不报失败`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val d = downloader { _, _ -> started.complete(Unit); gate.await() }
        d.start("u1", "j1")
        started.await()
        d.cancel()
        assertEquals(ExportDownloader.State.Idle, d.state.value)
        withTimeout(3000) { while (discarded.isEmpty()) delay(5) }
        assertEquals(listOf("u1"), discarded.toList())
        delay(50)
        assertEquals(ExportDownloader.State.Idle, d.state.value)
        // 取消后可以重新开始
        gate.complete(Unit)
        assertTrue(d.start("u3", "j1"))
        await(d, ExportDownloader.State.Succeeded)
    }

    @Test fun `start 后在首次调度前立刻 cancel 仍清空壳`() = runBlocking {
        // 单线程调度器先被占住，launch 的 body 排在队列里，保证 cancel 发生在首次调度之前
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ds = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
        try {
            val block = java.util.concurrent.CountDownLatch(1)
            exec.execute { block.await() }
            var ran = false
            val d = ExportDownloader(ds, { _, _, _ -> ran = true }, { discarded += it })
            assertTrue(d.start("u1", "j1"))
            d.cancel()
            block.countDown()
            withTimeout(3000) { while (discarded.isEmpty()) delay(5) }
            assertEquals(listOf("u1"), discarded.toList())
            assertFalse(ran)
            assertEquals(ExportDownloader.State.Idle, d.state.value)
        } finally { ds.cancel(); exec.shutdownNow() }
    }

    @Test fun `进度回调进入 progress 流 结束后清空`() = runBlocking {
        val mid = CompletableDeferred<Unit>()
        val d = ExportDownloader(scope, { _, _, report ->
            report(10, 100); mid.complete(Unit); gate.await()
        }, { discarded += it })
        d.start("u1", "j1")
        mid.await()
        assertEquals(DownloadProgress(10, 100), d.progress.value)
        gate.complete(Unit)
        await(d, ExportDownloader.State.Succeeded)
        assertEquals(null, d.progress.value)
    }

    @Test fun `未知总长度进度 known 为 false 且比例为 0`() = runBlocking {
        val mid = CompletableDeferred<Unit>()
        val d = ExportDownloader(scope, { _, _, report -> report(5, -1); mid.complete(Unit); gate.await() }, { discarded += it })
        d.start("u1", null)
        mid.await()
        assertFalse(d.progress.value!!.known)
        assertEquals(0f, d.progress.value!!.fraction)
        d.cancel()
        assertEquals(null, d.progress.value)
    }

    @Test fun `失败后进度清空 状态 Failed 可重试`() = runBlocking {
        val d = ExportDownloader(scope, { _, _, report -> report(1, 10); throw IOException("x") }, { discarded += it })
        d.start("u1", null)
        await(d, ExportDownloader.State.Failed)
        assertEquals(null, d.progress.value)
        d.consumeResult()
        assertTrue(d.start("u2", null))
    }
}
