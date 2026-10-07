package com.libeyond.imandroid.data

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删掉会话摘要指着的那条 → 重拉会话列表（对齐 iOS / Web）；其余删除不白拉。 */
class PreviewRefreshTest {
    @Test
    fun `删的是摘要那条——要刷新`() {
        assertTrue(PreviewRefresh.needed(lastConvSeq = 42, removed = listOf(42)))
        assertTrue(PreviewRefresh.needed(lastConvSeq = 42, removed = listOf(40, 41, 42)))
    }

    @Test
    fun `删的是更早的消息——摘要没变，不刷新`() {
        assertFalse(PreviewRefresh.needed(lastConvSeq = 42, removed = listOf(40, 41)))
    }

    @Test
    fun `会话还没有摘要位点——不刷新`() {
        assertFalse(PreviewRefresh.needed(lastConvSeq = 0, removed = listOf(0)))
    }

    @Test
    fun `短时间内多次请求只拉一次`() = runTest {
        var calls = 0
        val r = PreviewRefresher(this) { calls++ }
        repeat(5) { r.request() }
        advanceUntilIdle()
        assertEquals(1, calls)
        r.request()
        advanceUntilIdle()
        assertEquals(2, calls)
    }
}
