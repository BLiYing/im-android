package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件行的状态文案。**气泡与详情页文件行共用这一份**——
 * 两处各写一张表，迟早出现「气泡说下载失败、详情页说未下载」。
 */
class FileHintTest {

    @Test
    fun `就绪不写字——给已经下好的东西标一句是噪声`() {
        assertEquals("", DownloadPhase.Ready.fileHint())
    }

    @Test
    fun `五个未就绪态各有各的话`() {
        val hints = listOf(
            DownloadPhase.NotStarted, DownloadPhase.Downloading,
            DownloadPhase.Paused, DownloadPhase.Failed, DownloadPhase.Expired,
        ).map { it.fileHint() }
        // 每一条都非空且互不相同——合并任意两个都会让用户误解当前发生了什么
        assertTrue(hints.all { it.isNotEmpty() })
        assertEquals(hints.size, hints.toSet().size)
    }

    @Test
    fun `失败与失效说的不是一回事`() {
        // 失败可重试、失效不可——文案必须体现，否则用户会一直点一个永远不会好的东西
        assertTrue(DownloadPhase.Failed.fileHint().contains("重试"))
        assertTrue(DownloadPhase.Expired.fileHint().contains("失效"))
    }
}
