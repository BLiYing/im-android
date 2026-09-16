package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 查看器里点视频画面：开播 / 暂停 / 继续（iOS `togglePlayback`）。 */
class VideoTapTest {

    @Test
    fun `还没开播时点画面就开播`() {
        assertEquals(VideoTapAction.Start, VideoTap.actionFor(started = false, wantsPlay = false))
        assertEquals(VideoTapAction.Start, VideoTap.actionFor(started = false, wantsPlay = true))
    }

    @Test
    fun `在播或缓冲中想播时点一下是暂停——不能按 isPlaying 判`() {
        assertEquals(VideoTapAction.Pause, VideoTap.actionFor(started = true, wantsPlay = true))
    }

    @Test
    fun `暂停着点一下继续播`() {
        assertEquals(VideoTapAction.Resume, VideoTap.actionFor(started = true, wantsPlay = false))
    }
}
