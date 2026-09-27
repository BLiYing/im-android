package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.ReplySnapshots
import com.libeyond.imandroid.data.SysEvents
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.toEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.i18n.XmlStringResolver
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MessageData
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.buildChatRows
import com.libeyond.imandroid.ui.screens.localizeReplySnapshot
import com.libeyond.imandroid.ui.screens.quoteFileNameOf
import com.libeyond.imandroid.ui.screens.quoteSnapshotFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 引用快照结构化标记 `reply_snapshot_kind`/`_args`（P3 i18n，PROTOCOL §4.3）：[ReplySnapshots] 还原成原始 token，
 * 再由 `localizeReplySnapshot` 按 App 语言换前缀。对齐 Web `localizeReplySnapshot` 的覆盖面。
 */
class ReplySnapshotKindTest {

    @After
    fun restoreChinese() = Str.install(XmlStringResolver())

    private fun shown(kind: String?, args: Map<String, String>?, fallback: String?) =
        localizeReplySnapshot(ReplySnapshots.canonical(kind, SysEvents.encodeArgs(args), fallback).orEmpty())

    @Test
    fun `服务端预本地化的中文成品在英文下也能翻`() {
        Str.install(XmlStringResolver("en"))
        assertEquals("[Chat History] 周报", shown("chat_record", mapOf("title" to "周报"), "[聊天记录] 周报"))
        assertEquals("[Contact] 老王", shown("contact", mapOf("name" to "老王"), "[个人名片] 老王"))
        assertEquals("[Deleted message]", shown("recalled", null, "[已撤回的消息]"))
        assertEquals("[Call]", shown("call", null, "[音视频通话]"))
    }

    @Test
    fun `各 kind 的中文形态与服务端老字段一致`() {
        assertEquals("[聊天记录] 周报", shown("chat_record", mapOf("title" to "周报"), null))
        assertEquals("[聊天记录]", shown("chat_record", null, null))
        assertEquals("[文件] 报表.xlsx", shown("file", mapOf("name" to "报表.xlsx"), null))
        assertEquals("[文件]", shown("file", emptyMap(), null))
        assertEquals("[语音] 1:05", shown("voice", mapOf("duration_ms" to "65400"), null))
        assertEquals("[个人名片]", shown("contact", null, null))
        assertEquals("[图片]", shown("other", mapOf("content_type" to "image"), "[image]"))
        assertEquals("[视频]", shown("other", mapOf("content_type" to "video"), "[video]"))
    }

    @Test
    fun `坏数据与未知类型回退老字段`() {
        assertEquals("[语音] 0:00", shown("voice", mapOf("duration_ms" to "abc"), null))
        assertEquals("[语音] 0:00", shown("voice", mapOf("duration_ms" to "-5"), null))
        assertEquals("原文", shown("other", mapOf("content_type" to "sticker"), "原文"))
        assertEquals("原文", shown("future_kind", null, "原文"))
        // 纯文本引用不带 kind：原样显示用户输入，不做任何翻译
        assertEquals("在吗", shown(null, null, "在吗"))
        assertNull(ReplySnapshots.canonical(null, null, null))
    }

    @Test
    fun `文件名判据吃还原后的 token`() {
        val raw = ReplySnapshots.canonical("file", SysEvents.encodeArgs(mapOf("name" to "a.pdf")), "[file] a.pdf")!!
        assertEquals("a.pdf", quoteFileNameOf(raw))
    }

    @Test
    fun `落库后气泡引用条优先结构化标记`() {
        val row = MessageData(
            convId = "g_1", convSeq = 9, content = "收到", replyToConvSeq = 3,
            replySnapshot = "[聊天记录] 周报", replySnapshotKind = "chat_record", replySnapshotArgs = mapOf("title" to "周报"),
        ).toEntity("me")
        assertEquals("chat_record", row.replySnapshotKind)
        Str.install(XmlStringResolver("en"))
        assertEquals("[Chat History] 周报", localizeReplySnapshot(quoteSnapshotFor(emptyList(), row)!!))
    }

    private fun msg(seq: Long, type: String = ContentType.TEXT) = MessageEntity(
        ownerUid = "me", convId = "g_1", convSeq = seq, sender = if (type == ContentType.SYSTEM) "" else "u2",
        contentType = type, content = "x", timestamp = 1_700_000_000_000L + seq,
    )

    @Test
    fun `未读分割线不以群系统消息为首条`() {
        val rows = buildChatRows(
            listOf(msg(1), msg(2, ContentType.SYSTEM), msg(3)), emptyList(), readSeq = 1, unread = 1,
        )
        val divider = rows.indexOf(ChatRow.UnreadDivider)
        val next = rows[divider + 1] as ChatRow.Confirmed
        assertEquals(3L, next.msg.convSeq)
    }
}
