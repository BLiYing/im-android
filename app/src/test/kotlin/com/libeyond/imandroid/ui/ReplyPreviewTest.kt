package com.libeyond.imandroid.ui

import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.screens.replyPreviewOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 输入栏引用条的快照文案。
 *
 * **这组测试的由来**：此前这里直接 `content.take(60)`，引用一张图会显示
 * `/uploads/req-xxx__原名.jpg` 这样一串路径。口径必须与服务端冻结的 `reply_snapshot`
 * 一致（PROTOCOL §4.3）——否则"引用时看到的"和"发出去以后气泡里显示的"是两句话。
 */
class ReplyPreviewTest {

    @Test
    fun `媒体不显路径而显类型占位`() {
        val p = replyPreviewOf(ContentType.IMAGE, "/uploads/req-abc__照片.jpg", null, null)
        assertEquals("[图片]", p)
        assertFalse("绝不能把 URL 显出来", p.contains("uploads"))
    }

    @Test
    fun `图说跟在类型后面`() {
        assertEquals("[图片] 看这个", replyPreviewOf(ContentType.IMAGE, "/uploads/a.jpg", null, "看这个"))
        assertEquals("[视频]", replyPreviewOf(ContentType.VIDEO, "/uploads/a.mp4", null, ""))
    }

    /** 文件带原始文件名——引用条上分不清是哪个文件的话，引用就没意义了。 */
    @Test
    fun `文件带原名`() {
        assertEquals(
            "[文件] 报表.xlsx",
            replyPreviewOf(ContentType.FILE, "/uploads/req-1__报表.xlsx", null, null),
        )
        // fileName 显式给了就用它
        assertEquals(
            "[文件] 合同.pdf",
            replyPreviewOf(ContentType.FILE, "/uploads/req-2__x.bin", "合同.pdf", null),
        )
    }

    @Test
    fun `卡片类各有占位`() {
        assertEquals("[个人名片]", replyPreviewOf(ContentType.CONTACT, "{...}", null, null))
        assertEquals("[聊天记录]", replyPreviewOf(ContentType.CHAT_RECORD, "{...}", null, null))
        assertEquals("[语音]", replyPreviewOf(ContentType.VOICE, "/uploads/a.m4a", null, null))
    }

    @Test
    fun `文本原样`() {
        assertEquals("今天天气不错", replyPreviewOf(ContentType.TEXT, "今天天气不错", null, null))
    }
}
