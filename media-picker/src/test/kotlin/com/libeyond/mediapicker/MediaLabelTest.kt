package com.libeyond.mediapicker

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「原图 (x MB)」和视频时长角标的文案。 */
class MediaLabelTest {

    @Test
    fun `体积按 1024 进制且保留一位小数`() {
        assertEquals("0 B", MediaPick.sizeLabel(0))
        assertEquals("0 B", MediaPick.sizeLabel(-1))
        assertEquals("512 B", MediaPick.sizeLabel(512))
        // 1023 这个边界曾是测试的真空档：改成 1000 进制时 512 和 1024 两个断言都还是绿的
        assertEquals("1023 B", MediaPick.sizeLabel(1023))
        assertEquals("1.0 KB", MediaPick.sizeLabel(1024))
        assertEquals("1.0 MB", MediaPick.sizeLabel(1024L * 1024))
        assertEquals("2.50 GB", MediaPick.sizeLabel((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `时长是 m ss 且秒补零`() {
        assertEquals("0:05", MediaPick.durationLabel(5_000))
        assertEquals("1:00", MediaPick.durationLabel(60_000))
        assertEquals("2:07", MediaPick.durationLabel(127_400))
        assertEquals("0:00", MediaPick.durationLabel(0))
        assertEquals("0:00", MediaPick.durationLabel(-100))
    }

    @Test
    fun `原图总量只算选中的且按选中顺序无关`() {
        val a = MediaAsset(1, "u1", "image/jpeg", "a", 100, 1, "b", "B")
        val b = MediaAsset(2, "u2", "image/jpeg", "b", 250, 2, "b", "B")
        val c = MediaAsset(3, "u3", "image/jpeg", "c", 999, 3, "b", "B")
        assertEquals(350L, MediaPick.totalBytes(listOf(a, b, c), listOf(1, 2)))
        assertEquals(350L, MediaPick.totalBytes(listOf(a, b, c), listOf(2, 1)))
        assertEquals(0L, MediaPick.totalBytes(listOf(a, b, c), emptyList()))
    }
}
