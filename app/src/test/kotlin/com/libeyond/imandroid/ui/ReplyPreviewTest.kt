package com.libeyond.imandroid.ui

import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.screens.replyPreviewOf
import com.libeyond.imandroid.ui.screens.quoteFileNameOf
import com.libeyond.imandroid.ui.screens.localizeReplySnapshot
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
        val p = shown(ContentType.IMAGE, "/uploads/req-abc__照片.jpg", null, null)
        assertEquals("[图片]", p)
        assertFalse("绝不能把 URL 显出来", p.contains("uploads"))
    }

    @Test
    fun `图说跟在类型后面`() {
        assertEquals("[图片] 看这个", shown(ContentType.IMAGE, "/uploads/a.jpg", null, "看这个"))
        assertEquals("[视频]", shown(ContentType.VIDEO, "/uploads/a.mp4", null, ""))
    }

    /** 文件带原始文件名——引用条上分不清是哪个文件的话，引用就没意义了。 */
    @Test
    fun `文件带原名`() {
        assertEquals(
            "[文件] 报表.xlsx",
            shown(ContentType.FILE, "/uploads/req-1__报表.xlsx", null, null),
        )
        // fileName 显式给了就用它
        assertEquals(
            "[文件] 合同.pdf",
            shown(ContentType.FILE, "/uploads/req-2__x.bin", "合同.pdf", null),
        )
    }

    @Test
    fun `卡片类各有占位`() {
        assertEquals("[个人名片]", shown(ContentType.CONTACT, "{...}", null, null))
        assertEquals("[聊天记录]", shown(ContentType.CHAT_RECORD, "{...}", null, null))
        assertEquals("[语音]", shown(ContentType.VOICE, "/uploads/a.m4a", null, null))
    }

    @Test
    fun `文本原样`() {
        assertEquals("今天天气不错", shown(ContentType.TEXT, "今天天气不错", null, null))
    }

    /** 引用条显示的是原始快照经本地化后的文案（与气泡里的引用块同一条路径）。 */
    private fun shown(type: String, content: String, fileName: String?, caption: String?) =
        localizeReplySnapshot(replyPreviewOf(type, content, fileName, caption))

    /** 判据只认原始快照：本地化后的文案随界面语言变，拿它判断英文下会全部失效。 */
    @Test
    fun `文件名判据认原始 token 与存量中文`() {
        assertEquals("报表.xlsx", quoteFileNameOf(replyPreviewOf(ContentType.FILE, "/uploads/req-1__报表.xlsx", null, null)))
        assertEquals("合同.pdf", quoteFileNameOf("[文件] 合同.pdf"))
        assertEquals(null, quoteFileNameOf("[file]"))
        assertEquals(null, quoteFileNameOf("[image]"))
    }
}
