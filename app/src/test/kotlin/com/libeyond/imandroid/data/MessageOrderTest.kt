package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.buildChatRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息显示序——**三端共享的不变式**（`../IMServer/docs/SYMMETRY.md` 登记表）：
 *
 * > `timestamp` 主排；同毫秒时 `conv_seq=0`（待发/失败）视为 **+∞ 垫底**。
 *
 * 对端实现：iOS `IMDatabase.m` 的 `kIMMessageOrderAsc` + `IMChatViewController+Socket.m`
 * 的 `sortMessagesInPlace`；im-web `App.tsx`
 * `(a.timestamp - b.timestamp) || ((a.convSeq || MAX_SAFE_INTEGER) - (b.convSeq || MAX_SAFE_INTEGER))`。
 *
 * ### 为什么这条值得单独一组测试
 * iOS 2026-08-05 出过一次事故，注释里记着原话：**「从『临时垫底』变成『永久钉底』」**。
 * 起因是「conv_seq=0 一律垫底」——本意是让刚发出还没 ack 的消息显示在最底部，
 * 但**被拒收的消息永远 conv_seq=0**，于是后续收到的消息全插到它上面，
 * 用户滚到底只看到那条旧的失败消息，误以为新消息没收到。
 *
 * 本端 2026-09-07 撞见同一个形状：`buildChatRows` 把待发那一路**整段接在已确认之后**，
 * 注释还写着「待发消息恒在末尾——它们还没有服务端时间戳，用本地 createdAt，天然就是最新的」。
 * **那个假设对失败的消息不成立**：16:34 失败的那条，在 17:11 的消息到达后就不是最新的了。
 */
class MessageOrderTest {

    private fun msg(seq: Long, ts: Long) = MessageEntity(
        ownerUid = "me", convId = "c1", convSeq = seq, sender = "u1",
        contentType = ContentType.TEXT, content = "m$seq", timestamp = ts,
    )

    private fun pending(cid: String, ts: Long, failed: Boolean = false) = PendingMessageEntity(
        ownerUid = "me", clientMsgId = cid, convId = "c1", to = "u2",
        contentType = ContentType.TEXT, content = "p$cid", createdAt = ts,
        state = if (failed) "Failed" else "Sending",
    )

    private fun labels(rows: List<ChatRow>) = rows.mapNotNull {
        when (it) {
            is ChatRow.Confirmed -> it.msg.content
            is ChatRow.Pending -> it.msg.content
            else -> null
        }
    }

    // —— 比较器本身 ——

    @Test
    fun `conv_seq 为 0 视为正无穷垫底`() {
        assertEquals(Long.MAX_VALUE, MessageOrder.seqKey(0))
        assertEquals(7L, MessageOrder.seqKey(7))
        // 负数不该出现，但真出现了也按「未定序」处理，别排到最前面
        assertEquals(Long.MAX_VALUE, MessageOrder.seqKey(-1))
    }

    @Test
    fun `同毫秒时待发排在已确认之后`() {
        assertTrue(MessageOrder.compare(1000, 5, 1000, 0) < 0)
        assertTrue(MessageOrder.compare(1000, 0, 1000, 5) > 0)
        assertEquals(0, MessageOrder.compare(1000, 3, 1000, 3))
    }

    @Test
    fun `时间戳优先于 conv_seq`() {
        // 补拉的旧消息 conv_seq 很大但时间戳很旧，必须排到上面
        assertTrue(MessageOrder.compare(1000, 999, 2000, 1) < 0)
    }

    // —— 合流后的显示序（本次修的那条） ——

    @Test
    fun `失败的待发消息不再永久钉底`() {
        // iOS 2026-08-05 的事故形状：16:34 失败，17:11 又收到新消息 → 失败那条必须落回 16:34 的位置
        val rows = buildChatRows(
            confirmed = listOf(msg(1, 1000), msg(2, 3000)),
            pending = listOf(pending("f", 2000, failed = true)),
        )
        assertEquals(listOf("m1", "pf", "m2"), labels(rows))
    }

    @Test
    fun `发送中的消息仍然在最后——只要它真的最新`() {
        // 修这条不能把正常情况改坏：刚点发送的消息 createdAt 就是最新的，本来就该垫底
        val rows = buildChatRows(
            confirmed = listOf(msg(1, 1000), msg(2, 2000)),
            pending = listOf(pending("s", 9000)),
        )
        assertEquals(listOf("m1", "m2", "ps"), labels(rows))
    }

    @Test
    fun `同毫秒时待发排在已确认之后——合流后同样成立`() {
        val rows = buildChatRows(
            confirmed = listOf(msg(1, 5000)),
            pending = listOf(pending("p", 5000)),
        )
        assertEquals(listOf("m1", "pp"), labels(rows))
    }

    @Test
    fun `多条待发之间按 createdAt 保序`() {
        val rows = buildChatRows(
            confirmed = listOf(msg(1, 1000)),
            pending = listOf(pending("late", 8000), pending("early", 2000)),
        )
        assertEquals(listOf("m1", "pearly", "plate"), labels(rows))
    }
}
