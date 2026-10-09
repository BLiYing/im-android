package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图说画在哪。**存在的理由**：文件文的图说曾整段不画——判据挂在「贴边媒体」上，
 * 而文件气泡不贴边（2026-09-15 用户报「libeyond群」里文件 + 文字只显示文件卡）。
 */
class BubbleCaptionTest {

    @Test
    fun `文件文的图说画在文件卡下方`() {
        assertEquals(CaptionPlacement.UnderFile, BubbleCaption.placementOf("file", "看下这份报表"))
    }

    @Test
    fun `图文与视频文的图说画在贴边媒体下方`() {
        assertEquals(CaptionPlacement.UnderMedia, BubbleCaption.placementOf("image", "风景"))
        assertEquals(CaptionPlacement.UnderMedia, BubbleCaption.placementOf("video", "录屏"))
    }

    @Test
    fun `没有图说或只有空白就不画`() {
        listOf(null, "", "  \n").forEach { cap ->
            assertEquals(CaptionPlacement.None, BubbleCaption.placementOf("file", cap))
            assertEquals(CaptionPlacement.None, BubbleCaption.placementOf("image", cap))
        }
    }

    @Test
    fun `服务端不收图说的类型即使带了也不画`() {
        listOf("text", "voice", "contact", "chat_record", "system", null).forEach { type ->
            assertEquals(CaptionPlacement.None, BubbleCaption.placementOf(type, "脏数据"))
        }
    }
}
