package com.libeyond.imandroid.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionMergeTest {
    @Test fun `恰好一张图加文字合并为 caption`() = assertTrue(mergesIntoCaption(1, "图说", false))
    @Test fun `没有文字不合并`() = assertFalse(mergesIntoCaption(1, "", false))
    @Test fun `多张图是相册不带 caption`() = assertFalse(mergesIntoCaption(2, "图说", false))
    @Test fun `回复态不合并`() = assertFalse(mergesIntoCaption(1, "图说", true))
    @Test fun `没有图不合并`() = assertFalse(mergesIntoCaption(0, "图说", false))
}
