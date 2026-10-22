package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动删除后会话行预览的纯规则（v2：过期集合是窗口 [from, now-sec*1000]，不是前缀）：
 * 服务端快照回灌不盖本地重算 / 预览是否需要重算 / 重算时挑「最后一条可见」行。
 */
class AutoDeletePreviewTest {
    private val now = 1_000_000_000L
    private val day = 86_400
    private val dayMs = day * 1000L

    private fun conv(ts: Long, sec: Int = day, from: Long = 0, content: String = "hi", unread: Int = 0) = ConversationEntity(
        ownerUid = "me", convId = "c", lastContent = content, lastTimestamp = ts, lastConvSeq = 9, lastFrom = "u2",
        lastRecalled = true, lastSysEvent = "e", lastSysArgs = "a", lastSysSegments = "s", unread = unread,
        mentionUnread = unread > 0, autoDeleteSec = sec, autoDeleteFrom = from,
    )

    private fun msg(seq: Long, type: String = "text", deletedAt: Long? = null) =
        MessageEntity(ownerUid = "me", convId = "c", convSeq = seq, contentType = type, timestamp = seq, deletedAt = deletedAt)

    @Test
    fun `服务端预览为空 lastTimestamp 沿用本地 排序不沉底（from 之前也算）`() {
        val snap = conv(ts = 0, from = now - 3 * dayMs, content = "")
        val out = snap.keepAutoDeleteTail(conv(ts = now - 5 * dayMs), now)
        assertEquals(now - 5 * dayMs, out.lastTimestamp)
        assertEquals("", out.lastContent)
    }

    @Test
    fun `服务端给的预览按本机时钟落在窗口内 清空预览 保留时间 未读交给清理例程`() {
        val old = now - 2 * dayMs
        val out = conv(ts = old, from = old - 1, unread = 3).keepAutoDeleteTail(null, now)
        assertEquals("", out.lastContent)
        assertEquals("", out.lastFrom)
        assertEquals(false, out.lastRecalled)
        assertEquals("", out.lastSysEvent)
        assertEquals(old, out.lastTimestamp)
        assertEquals(3, out.unread) // 窗口之前的未读不能一刀清零，purgeExpired 按实际过期行扣
    }

    @Test
    fun `预览在 from 之前 永不过期 原样`() {
        val c = conv(ts = now - 5 * dayMs, from = now - dayMs, unread = 2)
        assertEquals(c, c.keepAutoDeleteTail(null, now))
    }

    @Test
    fun `未过期预览与档位关闭都原样`() {
        val fresh = conv(ts = now - 1000L, unread = 2)
        assertEquals(fresh, fresh.keepAutoDeleteTail(null, now))
        val off = conv(ts = 1L, sec = 0, unread = 2)
        assertEquals(off, off.keepAutoDeleteTail(null, now))
    }

    @Test
    fun `预览是否需要重算 窗口内 或 预览空但有时间`() {
        val old = now - 2 * dayMs
        assertTrue(AutoDeletePreview.stale(conv(ts = old), now))
        assertFalse(AutoDeletePreview.stale(conv(ts = old, from = old + 1), now)) // from 之前
        assertFalse(AutoDeletePreview.stale(conv(ts = now - 1000), now)) // 未满期
        assertFalse(AutoDeletePreview.stale(conv(ts = old, sec = 0), now))
        // 预览被清空（含 keepAutoDeleteTail 之后）但窗口之前还有可见消息：要能越过窗口重算
        assertTrue(AutoDeletePreview.stale(conv(ts = old, from = old + 1, content = "").copy(lastSysEvent = ""), now))
        // 会话从没有过消息（lastTimestamp=0）：无排序时间，不空转重算，即使 from=0 且预览空
        assertFalse(AutoDeletePreview.stale(conv(ts = 0, from = 0, content = "").copy(lastSysEvent = ""), now))
    }

    @Test
    fun `预览行跳过 msg_op 与已删墓碑 取剩下最新可见`() {
        val rows = listOf(msg(5, "msg_op"), msg(4, deletedAt = 1L), msg(3), msg(2))
        assertEquals(3L, AutoDeletePreview.pick(rows)?.convSeq)
        assertNull(AutoDeletePreview.pick(listOf(msg(5, "msg_op"))))
        assertTrue(AutoDeletePreview.pick(emptyList()) == null)
    }
}
