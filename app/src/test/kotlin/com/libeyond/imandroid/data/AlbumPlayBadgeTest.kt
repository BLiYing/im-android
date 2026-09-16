package com.libeyond.imandroid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宫格视频格中心的播放角标（iOS `IMAlbumTileView._playBadge`）。 */
class AlbumPlayBadgeTest {

    @Test
    fun `就绪的视频格画播放角标`() {
        assertTrue(AlbumLayout.showsPlayBadge(isVideo = true, sending = false, failed = false, gateReady = true))
    }

    @Test
    fun `图片格不画`() {
        assertFalse(AlbumLayout.showsPlayBadge(isVideo = false, sending = false, failed = false, gateReady = true))
    }

    @Test
    fun `中心位被上传环、失败标记或门控字形占着时让出去`() {
        assertFalse(AlbumLayout.showsPlayBadge(isVideo = true, sending = true, failed = false, gateReady = true))
        assertFalse(AlbumLayout.showsPlayBadge(isVideo = true, sending = false, failed = true, gateReady = true))
        assertFalse(AlbumLayout.showsPlayBadge(isVideo = true, sending = false, failed = false, gateReady = false))
    }
}
