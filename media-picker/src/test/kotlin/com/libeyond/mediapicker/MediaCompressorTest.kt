package com.libeyond.mediapicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩的纯算术部分（真解码要 Android 运行时，进不了 JVM 单测）。
 *
 * 这三件事错了都不报错、只是发出去的图不对：
 * 采样率算小了 → 一张 8000×6000 解出来 183MB 直接 OOM；
 * 采样率算大了 → 解出来比目标还小，放大回去糊；
 * 极端长条图缩完短边算成 0 → `createScaledBitmap` 当场抛。
 */
class MediaCompressorTest {

    @Test
    fun `采样率是 2 的幂且不会解得比目标小`() {
        // 8000 长边、目标 2048：/4 = 2000 < 2048，所以只能取 2（4000 ≥ 2048）
        assertEquals(2, MediaCompressor.sampleSize(8000, 6000, 2048))
        // 恰好 2 倍：/2 = 2048 ≥ 2048，可以取 2
        assertEquals(2, MediaCompressor.sampleSize(4096, 3000, 2048))
        // 比目标还小：不降采样
        assertEquals(1, MediaCompressor.sampleSize(1000, 800, 2048))
        assertEquals(1, MediaCompressor.sampleSize(2048, 100, 2048))
    }

    @Test
    fun `采样率结果必须是 2 的幂`() {
        for (w in listOf(3000, 5000, 9000, 17000, 40000)) {
            val s = MediaCompressor.sampleSize(w, w / 2, 2048)
            assertTrue("sample=$s 不是 2 的幂", s > 0 && (s and (s - 1)) == 0)
        }
    }

    @Test
    fun `坏尺寸不会算出 0 或负的采样率`() {
        assertEquals(1, MediaCompressor.sampleSize(0, 0, 2048))
        assertEquals(1, MediaCompressor.sampleSize(-5, 100, 2048))
        assertEquals(1, MediaCompressor.sampleSize(100, 100, 0))
    }

    @Test
    fun `等比缩放到长边并且不放大`() {
        assertEquals(2048 to 1536, MediaCompressor.targetSize(4096, 3072, 2048))
        assertEquals(1536 to 2048, MediaCompressor.targetSize(3072, 4096, 2048))
        // 本来就小于上限 → 原样，**不放大**（放大只会更糊还更大）
        assertEquals(800 to 600, MediaCompressor.targetSize(800, 600, 2048))
    }

    @Test
    fun `极端长条图短边至少 1px`() {
        // 20000×3 缩到长边 2048：短边按比例是 0.3，round 后是 0 —— 创建位图会当场抛
        val (w, h) = MediaCompressor.targetSize(20000, 3, 2048)
        assertEquals(2048, w)
        assertTrue("短边不能是 0，实际 $h", h >= 1)
    }

    @Test
    fun `EXIF 方向映射到旋转角`() {
        // 6 = ROTATE_90 / 3 = ROTATE_180 / 8 = ROTATE_270 / 1 = NORMAL
        assertEquals(90, MediaCompressor.rotationOf(6))
        assertEquals(180, MediaCompressor.rotationOf(3))
        assertEquals(270, MediaCompressor.rotationOf(8))
        assertEquals(0, MediaCompressor.rotationOf(1))
        assertEquals(0, MediaCompressor.rotationOf(0)) // UNDEFINED
    }

    @Test
    fun `旋转 90 或 270 度宽高互换其余不换`() {
        // 竖拍原图：存储 4000×3000 + EXIF 6（转 90），显示是 3000×4000
        assertEquals(3000 to 4000, MediaCompressor.displaySize(4000, 3000, 90))
        assertEquals(3000 to 4000, MediaCompressor.displaySize(4000, 3000, 270))
        assertEquals(4000 to 3000, MediaCompressor.displaySize(4000, 3000, 0))
        assertEquals(4000 to 3000, MediaCompressor.displaySize(4000, 3000, 180))
    }

    @Test
    fun `EXIF 方向 1 到 8 里换宽高的恰好是 5 6 7 8`() {
        val swapped = (0..8).filter {
            val (w, h) = MediaCompressor.displaySize(4000, 3000, MediaCompressor.rotationOf(it))
            w == 3000 && h == 4000
        }
        assertEquals(listOf(5, 6, 7, 8), swapped)
    }

    @Test
    fun `压过的文件名必须变成 jpg`() {
        // 字节已经是 JPEG，名字还写 heic 会让对端按 heic 解、必然失败
        assertEquals("IMG_0001.jpg", MediaCompressor.jpegNameFor("IMG_0001.HEIC"))
        assertEquals("photo.jpg", MediaCompressor.jpegNameFor("photo.png"))
        assertEquals("a.b.c.jpg", MediaCompressor.jpegNameFor("a.b.c.webp"))
        assertEquals("noext.jpg", MediaCompressor.jpegNameFor("noext"))
        assertEquals("image.jpg", MediaCompressor.jpegNameFor(""))
    }
}
