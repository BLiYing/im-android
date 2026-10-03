package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.UploadResult
import com.libeyond.imandroid.sdk.api.UploadSession
import com.libeyond.imandroid.sdk.api.UploadTransport
import com.libeyond.imandroid.sdk.http.ApiException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkedUploaderTest {
    /** 内存假服务端：顺序追加，offset 对不上就回自己当前 offset（与真服务端同语义）。 */
    private class FakeServer(val chunk: Int = 4) : UploadTransport {
        val received = java.io.ByteArrayOutputStream()
        val calls = mutableListOf<String>()
        var sessionAlive = true
        var failChunksWith: (() -> Throwable)? = null
        var failStatusWith: (() -> Throwable)? = null
        var failInitWith: (() -> Throwable)? = null
        var blockNextChunk: CompletableDeferred<Unit>? = null
        var forceOffset: Long? = null
        override suspend fun sessionInit(name: String, size: Long): UploadSession {
            calls += "init"; failInitWith?.let { throw it() }; received.reset(); sessionAlive = true
            return UploadSession("s1", 0, chunk)
        }
        override suspend fun sessionStatus(id: String): Long {
            calls += "status"
            failStatusWith?.let { throw it() }
            if (!sessionAlive) throw ApiException(500001, "gone")
            return received.size().toLong()
        }
        override suspend fun sessionChunk(id: String, offset: Long, bytes: ByteArray, len: Int, mime: String): Long {
            calls += "chunk@$offset"
            blockNextChunk?.let { blockNextChunk = null; it.await() }
            failChunksWith?.let { throw it() }
            if (offset == received.size().toLong()) received.write(bytes, 0, len)
            return forceOffset ?: received.size().toLong()
        }
        override suspend fun sessionComplete(id: String): UploadResult { calls += "complete"; return UploadResult("/u/x", "", received.size().toLong()) }
    }

    private fun file(n: Int): File = File.createTempFile("upload-test", ".bin").apply { writeBytes(ByteArray(n) { (it % 251).toByte() }); deleteOnExit() }

    private fun TestScopeUploader(server: FakeServer, f: File, id: String? = null, ids: MutableList<String> = mutableListOf(), prog: MutableList<Long> = mutableListOf()) =
        ChunkedUploader(server, f, "a.bin", "application/octet-stream", id, { ids += it }, { s, _ -> prog += s }, backoff = {}, io = kotlinx.coroutines.Dispatchers.Unconfined)

    @Test fun `正常上传：init、逐片、complete，内容逐字节一致`() = runTest {
        val s = FakeServer(); val f = file(10); val ids = mutableListOf<String>()
        val r = TestScopeUploader(s, f, ids = ids).run()
        assertEquals(10L, r.size)
        assertTrue(f.readBytes().contentEquals(s.received.toByteArray()))
        assertEquals(listOf("s1"), ids)
        assertEquals(listOf("init", "chunk@0", "chunk@4", "chunk@8", "complete"), s.calls)
    }

    @Test fun `有保存的 upload_id 先问 status 从服务端 offset 续传，不重发已收的`() = runTest {
        val s = FakeServer(); val f = file(10)
        s.received.write(f.readBytes(), 0, 8) // 服务端已收 8 字节
        TestScopeUploader(s, f, id = "s1").run()
        assertEquals(listOf("status", "chunk@8", "complete"), s.calls)
        assertTrue(f.readBytes().contentEquals(s.received.toByteArray()))
    }

    @Test fun `服务端回的 offset 与本地不一致时以服务端为准对齐继续`() = runTest {
        val s = FakeServer(); val f = file(10)
        s.received.write(f.readBytes(), 0, 4) // 服务端其实已有 4 字节，本地以为是 0
        // 无 id 新开：init 会清空——换成「有 id、status 回 4」更贴近；这里验证 chunk 回的 offset 被采用
        TestScopeUploader(s, f, id = "s1").run()
        assertTrue(f.readBytes().contentEquals(s.received.toByteArray()))
    }

    @Test fun `会话失效（业务错）清掉 id 重开，最多两次，之后抛出`() = runTest {
        val s = FakeServer(); val f = file(8); val ids = mutableListOf<String>()
        s.sessionAlive = false
        TestScopeUploader(s, f, id = "dead", ids = ids).run() // status 抛业务错 → 清 id → init 新会话
        assertEquals("", ids.first()) // 先清掉死 id
        assertEquals("s1", ids.last())
        assertTrue(s.calls.contains("init"))

        // init 也一直业务错：首次 + 重开 2 次 = 3 次 init，之后抛出（别无限循环——iOS 踩过的死循环）
        val s2 = FakeServer(); s2.failInitWith = { ApiException(500001, "gone") }
        try { TestScopeUploader(s2, f).run(); fail() } catch (e: ApiException) {
            assertEquals(500001, e.code)
            assertEquals(3, s2.calls.count { it == "init" })
        }
    }

    @Test fun `网络错保留会话，退避后接着传：失败两次后第三次成功`() = runTest {
        val ok = FakeServer(); val f = file(8); var fails = 2
        val flaky = object : UploadTransport by ok {
            override suspend fun sessionChunk(id: String, offset: Long, bytes: ByteArray, len: Int, mime: String): Long {
                if (fails-- > 0) throw ApiException(ApiException.TRANSPORT, "net")
                return ok.sessionChunk(id, offset, bytes, len, mime)
            }
        }
        val r = ChunkedUploader(flaky, f, "a", "x", null, {}, { _, _ -> }, backoff = {}, io = kotlinx.coroutines.Dispatchers.Unconfined).run()
        assertEquals(8L, r.size)
        assertEquals("会话没被重开（只有一次 init）", 1, ok.calls.count { it == "init" })
    }

    @Test fun `网络一直失败：连续两次重试后抛出，让用户点重试`() = runTest {
        val ok = FakeServer(); val f = file(8); var tries = 0
        val dead = object : UploadTransport by ok {
            override suspend fun sessionChunk(id: String, offset: Long, bytes: ByteArray, len: Int, mime: String): Long {
                tries++; throw ApiException(ApiException.TRANSPORT, "net")
            }
        }
        try { ChunkedUploader(dead, f, "a", "x", null, {}, { _, _ -> }, backoff = {}, io = kotlinx.coroutines.Dispatchers.Unconfined).run(); fail() } catch (e: ApiException) {
            assertTrue(e.isTransport)
            assertEquals("首次 + 重试 2 次 = 3 次", 3, tries)
        }
    }

    @Test fun `暂停掐掉在途一片，恢复后先问 status 再续传，内容完整`() = runTest {
        val s = FakeServer(); val f = file(12)
        val gate = CompletableDeferred<Unit>()
        s.blockNextChunk = gate // 第一片卡住，模拟在途
        val up = TestScopeUploader(s, f)
        val job = launch { up.run() }
        runCurrent()
        up.pause()          // 掐在途那片
        runCurrent()
        assertTrue(up.paused.value)
        up.resume()
        advanceUntilIdle()
        job.join()
        assertTrue("恢复路径要先 status 对齐", s.calls.contains("status"))
        assertTrue(f.readBytes().contentEquals(s.received.toByteArray()))
    }

    @Test fun `取消即外层取消，不再发后续请求`() = runTest {
        val s = FakeServer(); val f = file(12)
        val gate = CompletableDeferred<Unit>(); s.blockNextChunk = gate
        val job = launch { TestScopeUploader(s, f).run() }
        runCurrent()
        job.cancel(); advanceUntilIdle()
        assertTrue(s.calls.none { it == "complete" })
        assertEquals(1, s.calls.count { it.startsWith("chunk@") })
    }

    @Test fun `空文件是终态`() = runTest {
        try { TestScopeUploader(FakeServer(), file(0)).run(); fail() } catch (e: ApiException) { assertTrue(e.isTransport) }
    }
}
