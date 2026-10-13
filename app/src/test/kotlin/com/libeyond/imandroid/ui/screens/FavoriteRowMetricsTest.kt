package com.libeyond.imandroid.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 收藏页行高/尺寸钉死 iOS 的值（`IMFavoritesViewController.m heightForRow` 等），防被顺手改回。 */
class FavoriteRowMetricsTest {
    @Test
    fun `行高与 iOS heightForRow 一致`() {
        assertEquals(92.dp, FavoriteRowMetrics.TEXT_ROW_HEIGHT)
        assertEquals(92.dp, FavoriteRowMetrics.FILE_ROW_HEIGHT)
        assertEquals(108.dp, FavoriteRowMetrics.VOICE_ROW_HEIGHT)
        assertEquals(82.dp, FavoriteRowMetrics.CONTACT_ROW_HEIGHT)
        assertEquals(16.dp, ArchiveSeparatorInset)
    }

    @Test
    fun `文本行图标块与字号`() {
        assertEquals(52.dp, FavoriteRowMetrics.TILE)
        assertEquals(26.dp, FavoriteRowMetrics.TILE_GLYPH)
        assertEquals(2, FavoriteRowMetrics.TITLE_MAX_LINES)
        assertEquals(44.dp, FavoriteRowMetrics.AVATAR)
    }
}
