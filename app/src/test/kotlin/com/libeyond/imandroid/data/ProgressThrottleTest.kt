package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {
    private var t = 1000L
    private val th = ProgressThrottle(100, 0.01f) { t }

    @Test fun `首次放行 随后短间隔小增量被节流`() {
        assertTrue(th.shouldEmit(DownloadProgress(0, 1000)))
        t += 10
        assertFalse(th.shouldEmit(DownloadProgress(5, 1000)))
    }

    @Test fun `满 100ms 放行`() {
        th.shouldEmit(DownloadProgress(0, 1000))
        t += 100
        assertTrue(th.shouldEmit(DownloadProgress(1, 1000)))
    }

    @Test fun `增量达 1 百分点即使不满 100ms 也放行`() {
        th.shouldEmit(DownloadProgress(0, 1000))
        t += 1
        assertTrue(th.shouldEmit(DownloadProgress(10, 1000)))
    }

    @Test fun `完成必放行`() {
        th.shouldEmit(DownloadProgress(0, 1000))
        t += 1
        assertTrue(th.shouldEmit(DownloadProgress(1000, 1000)))
    }

    @Test fun `未知总长度只按时间节流`() {
        assertTrue(th.shouldEmit(DownloadProgress(1, -1)))
        t += 50
        assertFalse(th.shouldEmit(DownloadProgress(9_000_000, 0)))
        t += 50
        assertTrue(th.shouldEmit(DownloadProgress(9_000_001, -1)))
    }

    @Test fun `比例夹在 0 到 1`() {
        assertEquals(1f, DownloadProgress(200, 100).fraction)
        assertEquals(0.5f, DownloadProgress(50, 100).fraction)
    }
}
