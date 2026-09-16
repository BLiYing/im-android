package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复制」这一下复制什么（[copyKindOf]，对齐 iOS `copyMessageToPasteboard:`）。
 *
 * 本端此前**只给文本**，图片/视频/文件长按都没有「复制」——用户报的对齐项。
 * 这张矩阵里最容易写反的是 **caption 压过一切**：带图说的图片，「复制」作用在文本上，
 * 不是把图复制走（iOS 的第一条分支就是它）。
 */
class MessageCopyKindTest {

    private fun msg(
        type: String = ContentType.TEXT,
        content: String = "你好",
        caption: String? = null,
    ) = MessageEntity(
        ownerUid = "me", convId = "c1", convSeq = 1, sender = "u2",
        contentType = type, content = content, caption = caption,
    )

    @Test
    fun `纯文本复制文本`() {
        assertEquals(CopyKind.Text, copyKindOf(msg()))
    }

    @Test
    fun `图片复制图片`() {
        assertEquals(CopyKind.Image, copyKindOf(msg(ContentType.IMAGE, "/uploads/a.jpg")))
    }

    // iOS 的第一条分支：带图说时「这类消息的文本操作作用于文本」
    @Test
    fun `带图说的图片复制的是图说，不是图`() {
        assertEquals(CopyKind.Caption, copyKindOf(msg(ContentType.IMAGE, "/uploads/a.jpg", caption = "看这个")))
    }

    @Test
    fun `视频与文件复制链接`() {
        assertEquals(CopyKind.Link, copyKindOf(msg(ContentType.VIDEO, "/uploads/a.mp4")))
        assertEquals(CopyKind.Link, copyKindOf(msg(ContentType.FILE, "/uploads/a.pdf")))
    }

    // 刻意不跟 iOS：那边走兜底分支复制 message.content，也就是一段相对路径，对用户没意义
    @Test
    fun `语音不给复制`() {
        assertNull(copyKindOf(msg(ContentType.VOICE, "/uploads/a.m4a")))
    }

    @Test
    fun `系统消息不给复制`() {
        assertNull(copyKindOf(msg(ContentType.SYSTEM, "张三加入了群聊")))
    }

    @Test
    fun `内容为空的不给复制`() {
        assertNull(copyKindOf(msg(ContentType.IMAGE, "")))
        assertNull(copyKindOf(msg(ContentType.TEXT, "")))
    }

    // 菜单项的显隐与这张矩阵同源——分叉了就会出现「有『复制』但点了什么都没进剪贴板」
    @Test
    fun `菜单里给不给复制与矩阵一致`() {
        val img = msg(ContentType.IMAGE, "/uploads/a.jpg")
        assertTrue(MessageAction.Copy in MessageActions.availableFor(img, "me", isGroup = false, iAmManager = false))
        val voice = msg(ContentType.VOICE, "/uploads/a.m4a")
        assertTrue(MessageAction.Copy !in MessageActions.availableFor(voice, "me", isGroup = false, iAmManager = false))
    }
}
