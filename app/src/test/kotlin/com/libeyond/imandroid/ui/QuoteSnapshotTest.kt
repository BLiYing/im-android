package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.quoteSnapshotFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 引用块显示哪一份快照。
 *
 * **由来**：`ack` 只回 5 个字段（client_msg_id/server_msg_id/conv_id/conv_seq/timestamp），
 * 服务端**发送时冻结**的 `reply_snapshot` 回不来——于是自己发的引用消息在**自己这一侧**
 * 恒无快照，而对端一切正常。按"有快照才画引用块"写，自己看到的就是一条没有引用的消息。
 * 又是 ack 不回带那一族（forwardFrom → groupId → 媒体元数据 → fileName/fileSize → 这次）。
 */
class QuoteSnapshotTest {

    private fun msg(
        seq: Long,
        type: String = ContentType.TEXT,
        content: String = "正文",
        replyTo: Long? = null,
        snapshot: String? = null,
    ) = MessageEntity(
        ownerUid = "me", convId = "c", convSeq = seq, serverMsgId = "s$seq",
        sender = "me", contentType = type, content = content,
        replyToConvSeq = replyTo, replySnapshot = snapshot,
    )

    private fun rows(vararg m: MessageEntity): List<ChatRow> = m.map { ChatRow.Confirmed(it) }

    @Test
    fun `不是引用就不画`() {
        assertNull(quoteSnapshotFor(emptyList(), msg(1)))
        assertNull(quoteSnapshotFor(emptyList(), msg(1, replyTo = 0)))
    }

    /** 第一档：服务端冻结的快照最权威（原消息被删/撤回后仍可展示）。 */
    @Test
    fun `优先用服务端冻结的快照`() {
        val m = msg(2, replyTo = 1, snapshot = "[video]")
        assertEquals("[video]", quoteSnapshotFor(rows(msg(1), m), m))
    }

    /**
     * 第二档：自己发的那条没有冻结快照，从本地那条原消息现算。
     * **没有这一档，自己发的引用消息在自己这一侧就完全不显示引用块。**
     */
    @Test
    fun `没有冻结快照时按本地原消息现算`() {
        val original = msg(1, type = ContentType.VIDEO, content = "/uploads/a.mp4")
        val m = msg(2, replyTo = 1)
        assertEquals("[video]", quoteSnapshotFor(rows(original, m), m)) // 与服务端冻结快照同一形态，显示时再本地化
    }

    /** 第三档：原消息不在本地窗口里 → 「原消息」（同 iOS 的兜底文案），而不是空白。 */
    @Test
    fun `原消息不在窗口里就兜底`() {
        val m = msg(2, replyTo = 999)
        assertEquals("原消息", quoteSnapshotFor(rows(m), m))
    }

    /** 冻结快照是空串也要往下走，不能当"有快照"用。 */
    @Test
    fun `空串快照不算数`() {
        val original = msg(1, type = ContentType.FILE, content = "/uploads/req-1__报表.xlsx")
        val m = msg(2, replyTo = 1, snapshot = "")
        assertEquals("[file] 报表.xlsx", quoteSnapshotFor(rows(original, m), m))
    }
}
