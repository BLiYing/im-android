package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 待发件长按菜单（对齐 iOS `messageActionsForMessage:` 的 convSeq<=0 分支）。 */
class PendingMenuTest {
    private fun a(type: String, failed: Boolean, copy: Boolean = false) = PendingMenu.actions(type, failed, copy)

    @Test
    fun `发送中的媒体件只有取消发送`() {
        for (t in listOf(ContentType.IMAGE, ContentType.VIDEO, ContentType.FILE, ContentType.VOICE)) {
            assertEquals(t, listOf(PendingAction.CancelSend), a(t, failed = false))
        }
    }

    @Test
    fun `失败的媒体件取消发送加删除`() {
        assertEquals(listOf(PendingAction.CancelSend, PendingAction.Delete), a(ContentType.IMAGE, failed = true))
    }

    @Test
    fun `文本件发送中只有复制失败再加删除`() {
        assertEquals(listOf(PendingAction.Copy), a(ContentType.TEXT, failed = false, copy = true))
        assertEquals(listOf(PendingAction.Copy, PendingAction.Delete), a(ContentType.TEXT, failed = true, copy = true))
    }

    @Test
    fun `发送中的文本没文字可复制则无菜单`() {
        assertEquals(emptyList<PendingAction>(), a(ContentType.TEXT, failed = false))
    }
}
