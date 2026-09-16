package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「查看原视频」胶囊的判据（[OriginalVideo]）。
 *
 * 错法都是静默的：本地已有原件却还显胶囊（点了白下一遍）、下载中不显百分比（看着像卡住）、
 * 失效的还给点（每点一次拉一次 404）。
 */
class OriginalVideoTest {

    private fun label(
        isVideo: Boolean = true,
        hasLocal: Boolean = false,
        phase: DownloadPhase = DownloadPhase.NotStarted,
        fraction: Float = 0f,
        hasPercent: Boolean = false,
        sizeBytes: Long = 0,
    ) = OriginalVideo.chipLabel(isVideo, hasLocal, phase, fraction, hasPercent, sizeBytes)

    @Test
    fun `图片没有这枚胶囊`() {
        assertNull(label(isVideo = false))
    }

    @Test
    fun `本地已有原件就不显——看过一次不该再问一次`() {
        assertNull(label(hasLocal = true))
        assertNull(label(hasLocal = true, phase = DownloadPhase.Ready))
    }

    @Test
    fun `没下过时显文案，带上大小让用户先知道要花多少流量`() {
        assertEquals("查看原视频", label())
        assertEquals("查看原视频 · 1.0 MB", label(sizeBytes = 1024L * 1024))
    }

    @Test
    fun `下载中显百分比；总大小未知时只说下载中`() {
        assertEquals("下载中 42%", label(phase = DownloadPhase.Downloading, fraction = 0.42f, hasPercent = true))
        assertEquals("下载中…", label(phase = DownloadPhase.Downloading, fraction = 0f, hasPercent = false))
    }

    @Test
    fun `失败可重试，文案要说出来`() {
        assertEquals("下载失败，点击重试", label(phase = DownloadPhase.Failed))
    }

    @Test
    fun `失效是终态，胶囊直接不显——点了只会一次次拉 404`() {
        assertNull(label(phase = DownloadPhase.Expired))
    }

    @Test
    fun `暂停后回到可下载文案（本端没有续传，点了从头来）`() {
        assertEquals("查看原视频", label(phase = DownloadPhase.Paused))
    }

    @Test
    fun `下载中再点不重复发起`() {
        assertFalse(OriginalVideo.tapStartsDownload(DownloadPhase.Downloading))
        assertTrue(OriginalVideo.tapStartsDownload(DownloadPhase.NotStarted))
        assertTrue(OriginalVideo.tapStartsDownload(DownloadPhase.Failed))
    }

    // ————————————————— 下完那一刻要重算 —————————————————
    // 2026-09-16 用户报：下完了胶囊没消失，重进才消失。根因是查看器不重组，
    // 于是 hasLocal 与播放器用的本地文件都停在下载开始前的答案。

    @Test
    fun `只挑出已就绪的地址`() {
        val states = mapOf(
            "/u/a.mp4" to DownloadState(DownloadPhase.Ready),
            "/u/b.mp4" to DownloadState(DownloadPhase.Downloading, 10, 100),
            "/u/c.mp4" to DownloadState(DownloadPhase.Failed),
            "/u/d.mp4" to DownloadState(DownloadPhase.Expired),
        )
        assertEquals(setOf("/u/a.mp4"), OriginalVideo.readyUrls(states))
    }

    // 关键性质：进度推进**不能**改变这个集合，否则查看器会跟着每 64KB 重组一次
    @Test
    fun `进度推进不改变就绪集合`() {
        val a = mapOf("/u/b.mp4" to DownloadState(DownloadPhase.Downloading, 10, 100))
        val b = mapOf("/u/b.mp4" to DownloadState(DownloadPhase.Downloading, 90, 100))
        assertEquals(OriginalVideo.readyUrls(a), OriginalVideo.readyUrls(b))
        assertTrue(OriginalVideo.readyUrls(a).isEmpty())
    }

    @Test
    fun `空表回空集`() {
        assertTrue(OriginalVideo.readyUrls(emptyMap()).isEmpty())
    }
}
