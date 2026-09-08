package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动下载的**决策矩阵**。每条都对齐 iOS `IMShouldAutoDownload` / Web `shouldAutoDownload`——
 * 三端解释同一份服务端 JSON，判据分叉的表现是「同一条视频在这端自动下了、在那端要手点」，
 * 没有任何自动手段能发现。
 */
class DownloadPolicyTest {

    private val d = DownloadPolicy.defaults()
    private val MB = 1L shl 20

    @Test
    fun `出厂默认逐条对齐服务端 Defaults`() {
        assertEquals(10 * MB, d.cellular.video.maxBytes)
        assertEquals(1 * MB, d.cellular.file.maxBytes)
        assertEquals(15 * MB, d.wifi.video.maxBytes)
        assertEquals(3 * MB, d.wifi.file.maxBytes)
        // 图片恒 0 —— 这个 0 的含义是"无门槛恒自动"，不是"关闭"
        assertEquals(0L, d.wifi.image.maxBytes)
        assertTrue(d.wifi.enabled && d.cellular.enabled)
    }

    @Test
    fun `图片的 maxBytes 是 0 但仍然自动下`() {
        // 把这个 0 当成"关闭"是最容易犯的错：那样所有图片都要手点
        assertTrue(
            DownloadPolicy.shouldAutoDownload(d, "image", sizeBytes = 999L * MB, isGroup = false, onWifi = true),
        )
    }

    @Test
    fun `视频按大小闸，超上限不自动下`() {
        assertTrue(DownloadPolicy.shouldAutoDownload(d, "video", 14 * MB, false, onWifi = true))
        assertFalse(DownloadPolicy.shouldAutoDownload(d, "video", 16 * MB, false, onWifi = true))
    }

    @Test
    fun `大小未知也保守判否`() {
        // 让用户自己点 ↓，别赌一把去拉一个可能几百 MB 的东西
        assertFalse(DownloadPolicy.shouldAutoDownload(d, "video", 0, false, onWifi = true))
        assertFalse(DownloadPolicy.shouldAutoDownload(d, "file", -1, false, onWifi = true))
    }

    @Test
    fun `移动数据与 Wi-Fi 走各自的档`() {
        // 12MB 的视频：Wi-Fi 档（15MB）放行，移动数据档（10MB）拦下
        assertTrue(DownloadPolicy.shouldAutoDownload(d, "video", 12 * MB, false, onWifi = true))
        assertFalse(DownloadPolicy.shouldAutoDownload(d, "video", 12 * MB, false, onWifi = false))
    }

    @Test
    fun `网络总开关关掉时一切手动`() {
        val off = d.copy(wifi = d.wifi.copy(enabled = false))
        assertFalse(DownloadPolicy.shouldAutoDownload(off, "image", 1, false, onWifi = true))
    }

    @Test
    fun `单聊群聊各自的开关`() {
        val noGroup = d.copy(wifi = d.wifi.copy(image = d.wifi.image.copy(group = false)))
        assertTrue(DownloadPolicy.shouldAutoDownload(noGroup, "image", 1, isGroup = false, onWifi = true))
        assertFalse(DownloadPolicy.shouldAutoDownload(noGroup, "image", 1, isGroup = true, onWifi = true))
    }

    @Test
    fun `语音这类小东西不进阈值体系`() {
        // 草图 §08-07：体积极小的类型恒自动，否则用户要为每条语音点一次 ↓
        assertTrue(DownloadPolicy.shouldAutoDownload(d, "voice", 0, false, onWifi = false))
    }

    @Test
    fun `设置拉不到时按出厂默认走，不是全关`() {
        assertTrue(DownloadPolicy.shouldAutoDownload(null, "image", 1, false, onWifi = true))
    }

    @Test
    fun `档位反推与套用`() {
        assertEquals(DownloadPolicy.Tier.High, DownloadPolicy.tierOf(d.wifi))
        assertEquals(DownloadPolicy.Tier.Medium, DownloadPolicy.tierOf(d.cellular))
        val low = DownloadPolicy.applyTier(d.wifi, DownloadPolicy.Tier.Low)
        assertEquals(DownloadPolicy.Tier.Low, DownloadPolicy.tierOf(low))
        // 低档 = 视频/文件都手动，但**不动单/群开关**
        assertEquals(0L, low.video.maxBytes)
        assertTrue(low.video.single)
    }

    @Test
    fun `上限规整`() {
        assertEquals(0L, DownloadPolicy.clampBytes(-5))
        assertEquals(DownloadPolicy.MAX_AUTO_BYTES, DownloadPolicy.clampBytes(Long.MAX_VALUE))
    }

    @Test
    fun `isDefault 决定重置能不能点`() {
        assertTrue(DownloadPolicy.isDefault(d))
        assertFalse(DownloadPolicy.isDefault(d.copy(wifi = d.wifi.copy(enabled = false))))
    }
}

/** 下载状态机：五态各自该显示什么、点一下该做什么。 */
class DownloadStateTest {

    @Test
    fun `总大小未知时没有百分比，只能显示在动`() {
        val s = DownloadState(DownloadPhase.Downloading, received = 100, total = 0)
        assertEquals(0f, s.fraction, 0.001f)
        assertFalse(s.hasPercent)
    }

    @Test
    fun `进度夹在 0 到 1 之间`() {
        assertEquals(0.5f, DownloadState(DownloadPhase.Downloading, 50, 100).fraction, 0.001f)
        // 服务端 Content-Length 报小了也不能溢出成 1.2
        assertEquals(1f, DownloadState(DownloadPhase.Downloading, 200, 100).fraction, 0.001f)
    }

    @Test
    fun `失效是终态，点了不做事`() {
        // 给重试就是每点一次拉一次 404
        assertEquals(DownloadTap.None, DownloadState(DownloadPhase.Expired).tapAction())
    }

    @Test
    fun `暂停与没开始是两个态，但点了都是重新开始`() {
        assertEquals(DownloadTap.Start, DownloadState(DownloadPhase.Paused).tapAction())
        assertEquals(DownloadTap.Start, DownloadState(DownloadPhase.NotStarted).tapAction())
        assertEquals(DownloadTap.Start, DownloadState(DownloadPhase.Failed).tapAction())
    }

    @Test
    fun `下载中点了是暂停，就绪点了是打开`() {
        assertEquals(DownloadTap.Pause, DownloadState(DownloadPhase.Downloading).tapAction())
        assertEquals(DownloadTap.Open, DownloadState(DownloadPhase.Ready).tapAction())
    }
}
