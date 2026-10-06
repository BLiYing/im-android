package com.libeyond.mediapicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 封面抽帧的「走不走缩放请求」判定（真抽帧要 Android 运行时，进不了 JVM 单测）。 */
class VideoProbeTest {

    @Test
    fun `API 27 起且无旋转的大视频走解码端缩放`() {
        assertEquals(720 to 405, VideoProbe.scaledFrameSize(3840, 2160, 0, 27, 720))
        assertEquals(720 to 405, VideoProbe.scaledFrameSize(3840, 2160, 180, 34, 720))
    }

    @Test
    fun `API 26 没有这个方法`() {
        assertNull(VideoProbe.scaledFrameSize(3840, 2160, 0, 26, 720))
    }

    @Test
    fun `旋转 90 或 270 的不走缩放请求避免封面被拉伸`() {
        assertNull(VideoProbe.scaledFrameSize(3840, 2160, 90, 34, 720))
        assertNull(VideoProbe.scaledFrameSize(3840, 2160, 270, 34, 720))
    }

    @Test
    fun `本来就不大的视频不缩放`() {
        assertNull(VideoProbe.scaledFrameSize(640, 360, 0, 34, 720))
        assertNull(VideoProbe.scaledFrameSize(720, 400, 0, 34, 720))
    }

    @Test
    fun `读不到尺寸时不请求缩放`() {
        assertNull(VideoProbe.scaledFrameSize(0, 0, 0, 34, 720))
        assertNull(VideoProbe.scaledFrameSize(-1, 100, 0, 34, 720))
    }
}
