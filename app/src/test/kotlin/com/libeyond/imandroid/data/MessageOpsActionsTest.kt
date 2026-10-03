package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑 / 翻译 / 举报三项的菜单门控（对齐 iOS `messageActionsForMessage:mine:`，顺序：… 撤回 · 置顶 · 编辑 · 多选 · 翻译 · 举报 · 删除）。 */
class MessageOpsActionsTest {
    private val ME = "1"
    private val OTHER = "2"

    private fun msg(sender: String, type: String = ContentType.TEXT, content: String = "hi", seq: Long = 10, recalledAt: Long? = null) =
        MessageEntity(
            ownerUid = ME, convId = "g", convSeq = seq, sender = sender, contentType = type, content = content,
            timestamp = 1_700_000_000_000L, recalledAt = recalledAt,
        )

    private fun acts(m: MessageEntity, group: Boolean = true) =
        MessageActions.availableFor(m, ME, group, false, now = 1_700_000_000_000L + 3_600_000L)

    @Test fun `本人文本消息能编辑，别人的不能`() {
        assertTrue(MessageAction.Edit in acts(msg(ME)))
        assertFalse(MessageAction.Edit in acts(msg(OTHER)))
    }

    @Test fun `编辑只给文本、非空、已确认的`() {
        assertFalse(MessageAction.Edit in acts(msg(ME, type = ContentType.IMAGE)))
        assertFalse(MessageAction.Edit in acts(msg(ME, content = "  ")))
        assertTrue("待确认行没有任何菜单", acts(msg(ME, seq = 0)).isEmpty())
    }

    @Test fun `翻译只给非空文本消息，本人别人都给`() {
        assertTrue(MessageAction.Translate in acts(msg(ME)))
        assertTrue(MessageAction.Translate in acts(msg(OTHER)))
        assertFalse(MessageAction.Translate in acts(msg(OTHER, type = ContentType.VOICE)))
        assertFalse(MessageAction.Translate in acts(msg(OTHER, content = "")))
    }

    @Test fun `举报只给别人的消息，自己的与系统的不给`() {
        assertTrue(MessageAction.Report in acts(msg(OTHER)))
        assertFalse(MessageAction.Report in acts(msg(ME)))
        assertFalse(MessageAction.Report in acts(msg(OTHER, type = ContentType.SYSTEM)))
        assertFalse(MessageAction.Report in acts(msg(DetailActions.SYSTEM_UID)))
    }

    @Test fun `菜单顺序：多选 翻译 举报 都在删除之前`() {
        val a = acts(msg(OTHER))
        assertTrue(a.indexOf(MessageAction.MultiSelect) < a.indexOf(MessageAction.Translate))
        assertTrue(a.indexOf(MessageAction.Translate) < a.indexOf(MessageAction.Report))
        assertTrue(a.indexOf(MessageAction.Report) < a.indexOf(MessageAction.HideForMe))
    }

    @Test fun `撤回的消息什么都没有`() = assertEquals(emptyList<MessageAction>(), acts(msg(OTHER, recalledAt = 5)))
}
