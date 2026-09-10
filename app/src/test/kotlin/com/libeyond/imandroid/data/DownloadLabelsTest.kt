package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.DownloadLabels.FileSlot
import com.libeyond.imandroid.data.DownloadLabels.Glyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未下载媒体的状态文案与图标（iOS `IMDownloadProgress` / `IMImageCell` / `IMAlbumTileView` / `IMBubbleCell` 同表）。
 * 2026-09-10 用户报 #5/#6/#7/#8：三类未下载媒体的各状态与 iOS 不一致、未下载文件露出类型图标。
 */
class DownloadLabelsTest {

    private val mb2 = 2L * 1024 * 1024
    private fun st(phase: DownloadPhase, received: Long = 0, total: Long = 0) = DownloadState(phase, received, total)

    /** #8 后半条：没下下来时显类型图标，看着像已经能打开。 */
    @Test
    fun `未下载的文件看不到类型图标，只有就绪才显`() {
        assertEquals(FileSlot.TypeIcon, DownloadLabels.fileSlotOf(DownloadPhase.Ready))
        DownloadPhase.entries.filter { it != DownloadPhase.Ready }.forEach {
            assertTrue("$it 不该显类型图标", DownloadLabels.fileSlotOf(it) != FileSlot.TypeIcon)
        }
        assertEquals(FileSlot.StartDisc, DownloadLabels.fileSlotOf(DownloadPhase.NotStarted))
        assertEquals(FileSlot.RingPause, DownloadLabels.fileSlotOf(DownloadPhase.Downloading))
        assertEquals(FileSlot.RingResume, DownloadLabels.fileSlotOf(DownloadPhase.Paused))
    }

    @Test
    fun `中心图标与 iOS 同表：未开始和暂停是下载、下载中是暂停、失败是重试`() {
        assertEquals(Glyph.Download, DownloadLabels.glyphOf(DownloadPhase.NotStarted))
        assertEquals(Glyph.Download, DownloadLabels.glyphOf(DownloadPhase.Paused))
        assertEquals(Glyph.Pause, DownloadLabels.glyphOf(DownloadPhase.Downloading))
        assertEquals(Glyph.Retry, DownloadLabels.glyphOf(DownloadPhase.Failed))
        assertEquals(Glyph.None, DownloadLabels.glyphOf(DownloadPhase.Expired))
        assertEquals(Glyph.None, DownloadLabels.glyphOf(DownloadPhase.Ready))
    }

    @Test
    fun `未下载的视频左上角是大小加时长，图片只有大小`() {
        assertEquals("2.0 MB · 0:12", DownloadLabels.mediaCapsule(st(DownloadPhase.NotStarted), mb2, "0:12"))
        assertEquals("2.0 MB", DownloadLabels.mediaCapsule(st(DownloadPhase.NotStarted), mb2, null))
        assertNull("大小时长都没有就不显空胶囊", DownloadLabels.mediaCapsule(st(DownloadPhase.NotStarted), 0, null))
    }

    /** 两项并排在窄图上会溢出，iOS 下载中只留进度。 */
    @Test
    fun `下载中只显进度、藏掉时长`() {
        val s = st(DownloadPhase.Downloading, received = mb2 / 2, total = mb2)
        assertEquals("1.0 MB / 2.0 MB", DownloadLabels.mediaCapsule(s, mb2, "0:12"))
        assertEquals("等待中", DownloadLabels.mediaCapsule(st(DownloadPhase.Downloading), mb2, "0:12"))
    }

    @Test
    fun `服务端没回总大小时用消息上的大小算进度`() {
        assertEquals("1.0 MB / 2.0 MB", DownloadLabels.progressText(st(DownloadPhase.Downloading, received = mb2 / 2), mb2))
        assertEquals("512 B", DownloadLabels.progressText(st(DownloadPhase.Downloading, received = 512), 0))
    }

    /** 本端暂停会删半截文件，已收恒为 0；照 iOS 写会显示成「等待中」。 */
    @Test
    fun `暂停显已暂停，环仍在但只露一点头`() {
        val paused = st(DownloadPhase.Paused)
        assertEquals("已暂停", DownloadLabels.mediaCapsule(paused, mb2, "0:12"))
        assertTrue(DownloadLabels.showsRing(DownloadPhase.Paused))
        assertEquals(0.02f, DownloadLabels.ringFraction(paused, mb2), 0.0001f)
        assertFalse(DownloadLabels.showsRing(DownloadPhase.NotStarted))
        assertFalse(DownloadLabels.showsRing(DownloadPhase.Failed))
    }

    @Test
    fun `失败的胶囊标红，失效与就绪不画胶囊`() {
        assertEquals("下载失败", DownloadLabels.mediaCapsule(st(DownloadPhase.Failed), mb2, "0:12"))
        assertTrue(DownloadLabels.capsuleIsDanger(DownloadPhase.Failed))
        assertFalse(DownloadLabels.capsuleIsDanger(DownloadPhase.Downloading))
        assertNull(DownloadLabels.mediaCapsule(st(DownloadPhase.Expired), mb2, "0:12"))
        assertNull(DownloadLabels.mediaCapsule(st(DownloadPhase.Ready), mb2, "0:12"))
    }

    @Test
    fun `文件状态行五态`() {
        assertEquals("2.0 MB · 点击下载", DownloadLabels.fileStatusLine(st(DownloadPhase.NotStarted), mb2))
        assertEquals("点击下载", DownloadLabels.fileStatusLine(st(DownloadPhase.NotStarted), 0))
        assertEquals("1.0 MB / 2.0 MB", DownloadLabels.fileStatusLine(st(DownloadPhase.Downloading, mb2 / 2, mb2), mb2))
        assertEquals("下载失败，点击重试", DownloadLabels.fileStatusLine(st(DownloadPhase.Failed), mb2))
        assertEquals("文件已失效", DownloadLabels.fileStatusLine(st(DownloadPhase.Expired), mb2))
        assertEquals("就绪只剩大小", "2.0 MB", DownloadLabels.fileStatusLine(st(DownloadPhase.Ready), mb2))
        assertTrue(DownloadLabels.fileStatusIsDanger(DownloadPhase.Failed))
        assertTrue(DownloadLabels.fileStatusIsDanger(DownloadPhase.Expired))
        assertFalse(DownloadLabels.fileStatusIsDanger(DownloadPhase.NotStarted))
    }

    @Test
    fun `宫格角标只放一项，失效不显`() {
        assertEquals("2.0 MB", DownloadLabels.tileCaption(st(DownloadPhase.NotStarted), mb2))
        assertEquals("下载失败", DownloadLabels.tileCaption(st(DownloadPhase.Failed), mb2))
        assertNull(DownloadLabels.tileCaption(st(DownloadPhase.Expired), mb2))
        assertNull(DownloadLabels.tileCaption(st(DownloadPhase.Ready), mb2))
    }
}
