package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「数据和存储」设置页的判据与文案。每条都对着 iOS `IMDownloadSettingsUI` 与两个设置 VC——
 * 分叉的表现是同一账号在两台手机上看到的设置写法不同。
 */
class DownloadSettingsUiTest {

    private val d = DownloadPolicy.defaults()
    private val MB = 1L shl 20
    private val ui = DownloadSettingsUi

    @Test
    fun `大小档位 13 档，右端正是服务端上限`() {
        assertEquals(13, ui.SIZE_STOPS.size)
        assertEquals(0L, ui.SIZE_STOPS.first())
        assertEquals(DownloadPolicy.MAX_AUTO_BYTES, ui.SIZE_STOPS.last())
    }

    @Test
    fun `服务端值不在档位上时落到最近一档，等距取靠左`() {
        assertEquals(0, ui.sizeStopIndex(0))
        assertEquals(5, ui.sizeStopIndex(10 * MB))
        // 20MB 离 15MB（第 6 档）更近
        assertEquals(6, ui.sizeStopIndex(20 * MB))
        // 22.5MB 与 15MB / 30MB 等距 → 取左
        assertEquals(6, ui.sizeStopIndex(45 * MB / 2))
        assertEquals(12, ui.sizeStopIndex(4096 * MB))
    }

    @Test
    fun `上限 0 写「关」，其余与 iOS 同一套字节格式`() {
        assertEquals("关", ui.sizeLabel(0))
        assertEquals("512 KB", ui.sizeLabel(512L * 1024))
        assertEquals("1 GB", ui.sizeLabel(1024 * MB))
        assertEquals("1.5 GB", ui.sizeLabel(1536 * MB))
    }

    @Test
    fun `字节格式逐条对齐 IMFormatFileSize`() {
        assertEquals("0 KB", ui.formatBytes(0))
        assertEquals("", ui.formatBytes(-1))
        // 不足 0.1 KB 按 0.1 显示，不出现 B 单位
        assertEquals("0.1 KB", ui.formatBytes(1))
        assertEquals("1.5 KB", ui.formatBytes(1536))
        // 接近整数去掉小数：「3 MB」不是「3.0 MB」
        assertEquals("3 MB", ui.formatBytes(3 * MB))
        assertEquals("1.9 MB", ui.formatBytes(19 * MB / 10))
    }

    @Test
    fun `网络行副标题`() {
        assertEquals("视频 15 MB · 文件 3 MB", ui.networkSummary(d.wifi))
        assertEquals("视频 10 MB · 文件 1 MB", ui.networkSummary(d.cellular))
        assertEquals("已停用", ui.networkSummary(d.wifi.copy(enabled = false)))
        val low = DownloadPolicy.applyTier(d.wifi, DownloadPolicy.Tier.Low)
        assertEquals("视频 关 · 文件 关", ui.networkSummary(low))
    }

    @Test
    fun `类别行右值照抄 iOS，图片那行不看开关`() {
        val noGroup = d.wifi.copy(image = d.wifi.image.copy(group = false))
        assertEquals("对所有聊天启用", ui.categoryValue(noGroup, DownloadCategory.Image))
        assertEquals("最大 15 MB", ui.categoryValue(d.wifi, DownloadCategory.Video))
        assertEquals("最大 3 MB", ui.categoryValue(d.wifi, DownloadCategory.File))
    }

    @Test
    fun `只有自定义时才出现第四档`() {
        assertEquals(listOf("低", "中", "高"), ui.tierNames(custom = false))
        assertEquals(listOf("低", "中", "高", "自定义"), ui.tierNames(custom = true))
    }

    @Test
    fun `档位松手：换档套用上限、不动单群开关`() {
        val medium = d.cellular.copy(video = d.cellular.video.copy(group = false))
        val high = ui.commitTier(medium, 2)
        assertEquals(15 * MB, high?.video?.maxBytes)
        assertEquals(3 * MB, high?.file?.maxBytes)
        assertEquals(false, high?.video?.group)
    }

    @Test
    fun `档位松手：停在原档不保存`() {
        assertNull(ui.commitTier(d.cellular, 1))
    }

    @Test
    fun `档位松手：自定义态停在第四档不保存，拖到低档才套用`() {
        val custom = d.wifi.copy(video = d.wifi.video.copy(maxBytes = 20 * MB))
        assertNull(ui.commitTier(custom, 3))
        assertEquals(DownloadPolicy.Tier.Low, ui.commitTier(custom, 0)?.let(DownloadPolicy::tierOf))
    }

    @Test
    fun `档位松手：非自定义态越界夹到高档`() {
        assertEquals(DownloadPolicy.Tier.High, ui.commitTier(d.cellular, 3)?.let(DownloadPolicy::tierOf))
    }

    @Test
    fun `大小松手：换档写档位值，原档不保存`() {
        val r = d.wifi.video
        assertEquals(30 * MB, ui.commitSize(r, 7)?.maxBytes)
        assertNull(ui.commitSize(r, ui.sizeStopIndex(r.maxBytes)))
        assertEquals(1536 * MB, ui.commitSize(r, 99)?.maxBytes)
    }

    @Test
    fun `改一个网络的一类不串到别处`() {
        val video = CategoryRule(single = false, group = true, maxBytes = 50 * MB)
        val s = ui.withPolicy(d, DownloadNetwork.Cellular, ui.withRule(d.cellular, DownloadCategory.Video, video))
        assertEquals(d.wifi, s.wifi)
        assertEquals(d.cellular.file, s.cellular.file)
        assertEquals(d.cellular.image, s.cellular.image)
        assertEquals(video, ui.ruleOf(ui.policyOf(s, DownloadNetwork.Cellular), DownloadCategory.Video))
    }

    @Test
    fun `存储用量与清除提示`() {
        assertEquals("0 KB", ui.usageLabel(null))
        assertEquals("0 KB", ui.usageLabel(0))
        assertEquals("暂无可清除的缓存。", ui.clearCacheMessage(0))
        assertTrue(ui.clearCacheMessage(3 * MB).contains("3 MB"))
    }
}
