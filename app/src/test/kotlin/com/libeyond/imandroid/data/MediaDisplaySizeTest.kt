package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.MediaDisplaySize.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** 媒体气泡尺寸，照 iOS `IMMediaDisplaySize`（十七条对齐 #16）。 */
class MediaDisplaySizeTest {

    private val box = Size(240f, 320f)

    @Test
    fun `框宽取 240 与屏宽六成二的较小者`() {
        assertEquals(Size(240f, 320f), MediaDisplaySize.box(411f))
        assertEquals(223.2f, MediaDisplaySize.box(360f).width, 0.01f)
    }

    @Test
    fun `横图按宽缩、竖图按高缩`() {
        assertEquals(Size(240f, 180f), MediaDisplaySize.fit(4000, 3000, box))
        assertEquals(Size(180f, 320f), MediaDisplaySize.fit(1080, 1920, box))
    }

    @Test
    fun `小图不放大`() {
        assertEquals(Size(120f, 100f), MediaDisplaySize.fit(120, 100, box))
    }

    @Test
    fun `短边不足 80 等比放大，再逐维夹回框内`() {
        assertEquals(Size(160f, 80f), MediaDisplaySize.fit(100, 50, box))
        // 3000×100 → 240×8 → ×10 = 2400×80 → 宽夹回 240
        assertEquals(Size(240f, 80f), MediaDisplaySize.fit(3000, 100, box))
    }

    @Test
    fun `取整是远离零而不是银行家舍入`() {
        // 250×125 在宽 125 的框里 k=0.5 → 125×62.5 → 63（银行家舍入会得 62）。关掉短边下限才看得到这一步
        assertEquals(Size(125f, 63f), MediaDisplaySize.fit(250, 125, Size(125f, 320f), minSide = 0f))
    }

    @Test
    fun `服务端没给宽高时画方块`() {
        assertEquals(Size(180f, 180f), MediaDisplaySize.fit(null, null, box))
        assertEquals(Size(100f, 100f), MediaDisplaySize.fit(0, 600, Size(100f, 320f)))
    }

    @Test
    fun `框无效返回零`() {
        assertEquals(Size(0f, 0f), MediaDisplaySize.fit(100, 100, Size(0f, 320f)))
    }
}
