package com.libeyond.imandroid.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「不吞协程取消」的 runCatching。
 *
 * 为什么值得一条测试：裸 `runCatching` 吞掉 `CancellationException` 后，**编译过、
 * 界面看起来也正常**——只有在「用户退出页面的同一瞬间请求失败」这种时机才露头，
 * 表现为往已销毁的页面弹提示。这类坑靠肉眼永远看不出来。
 */
class RunCatchingCancellableTest {

    @Test
    fun `正常返回值原样带出`() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
    }

    @Test
    fun `普通异常被收成失败结果`() {
        val r = runCatchingCancellable<Int> { throw IllegalStateException("boom") }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `协程取消原样抛出而不是收成失败结果`() {
        try {
            runCatchingCancellable<Int> { throw CancellationException("cancelled") }
            fail("CancellationException 必须继续往外抛，否则协程取消失效")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }
}
