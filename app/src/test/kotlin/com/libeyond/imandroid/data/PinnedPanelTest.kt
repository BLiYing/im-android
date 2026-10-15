package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.PinnedMessage
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MessageData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 置顶面板纯判据（PINNED_PANEL_DESIGN §2 / §4.0 / §6）：数据兜底、长按菜单过滤、权限显隐、图标左右、日期胶囊。
 * 与 iOS `IMPinnedMessageTests`、im-web `App.pinnedBanner.test.tsx` 同一组用例。
 */
class PinnedPanelTest {
    private fun row(seq: Long, type: String = ContentType.TEXT, content: String = "本地", recalledAt: Long? = null) =
        MessageEntity(ownerUid = "1", convId = "g", convSeq = seq, sender = "2", contentType = type, content = content, timestamp = 5, recalledAt = recalledAt)

    private fun item(seq: Long, message: MessageData? = null) =
        PinnedMessage(convSeq = seq, sender = "2", fromNickname = "张三", contentType = ContentType.IMAGE, content = "/u/a.jpg", timestamp = 9, pinnedAt = 100, message = message)

    private val fallback: (PinnedMessage) -> String = { "[图片]" }

    @Test fun `兜底顺序：本地行优先`() {
        val m = PinnedPanel.resolve(item(7, MessageData(convId = "g", convSeq = 7, from = "2", contentType = ContentType.IMAGE, content = "/u/a.jpg")), row(7), "1", "g", fallback)
        assertEquals("本地", m.content)
    }

    @Test fun `兜底顺序：本地没有（或本地是撤回墓碑）→ 接口带的完整消息对象，媒体元数据都在`() {
        val api = MessageData(convId = "", convSeq = 7, from = "2", contentType = ContentType.IMAGE, content = "/u/a.jpg", mediaW = 400, mediaH = 300)
        for (local in listOf(null, row(7, recalledAt = 3))) {
            val m = PinnedPanel.resolve(item(7, api), local, "1", "g", fallback)
            assertEquals(ContentType.IMAGE, m.contentType)
            assertEquals(400, m.mediaW)
            assertEquals("message 里缺 conv_id 时补成本会话", "g", m.convId)
            assertEquals("1", m.ownerUid)
        }
    }

    @Test fun `兜底顺序：旧服务端无 message → 类型词纯文本气泡，保留发送者与时间`() {
        val m = PinnedPanel.resolve(item(7), null, "1", "g", fallback)
        assertEquals(ContentType.TEXT, m.contentType)
        assertEquals("[图片]", m.content)
        assertEquals("2", m.sender)
        assertEquals("张三", m.fromNickname)
        assertEquals(9L, m.timestamp)
        assertEquals(7L, m.convSeq)
    }

    @Test fun `长按菜单：有权限 = 取消置顶 · 复制 · 定位；非文本无复制`() {
        assertEquals(
            listOf(PinnedMenuAction.Unpin, PinnedMenuAction.Copy, PinnedMenuAction.Locate),
            PinnedPanel.menuActions(row(1), canUnpin = true),
        )
        assertEquals(
            listOf(PinnedMenuAction.Unpin, PinnedMenuAction.Locate),
            PinnedPanel.menuActions(row(1, type = ContentType.IMAGE), canUnpin = true),
        )
    }

    @Test fun `无权限：菜单无取消项、无「取消所有」底栏、标题栏无副标题`() {
        assertEquals(listOf(PinnedMenuAction.Copy, PinnedMenuAction.Locate), PinnedPanel.menuActions(row(1), canUnpin = false))
        assertFalse(PinnedPanel.showsUnpinAll(canUnpin = false, count = 3))
        assertFalse(PinnedPanel.showsLongPressHint(canUnpin = false))
        assertTrue(PinnedPanel.showsUnpinAll(canUnpin = true, count = 3))
        assertTrue(PinnedPanel.showsLongPressHint(canUnpin = true))
        assertFalse("空集不出底栏", PinnedPanel.showsUnpinAll(canUnpin = true, count = 0))
    }

    @Test fun `长按取消置顶只作用于所长按那条（不是横幅显示的最新那条）`() {
        val items = listOf(PinnedMessage(convSeq = 9, pinnedAt = 300), PinnedMessage(convSeq = 7, pinnedAt = 200), PinnedMessage(convSeq = 5, pinnedAt = 100))
        val left = PinnedPanel.without(items, listOf(5L))
        assertEquals(listOf(9L, 7L), left.map { it.convSeq })
        assertEquals("横幅仍是最新那条", 9L, PinnedBanner.latest(left)?.convSeq)
    }

    @Test fun `取消所有置顶的 targets 批量摘除；不相干的 seq 不动`() {
        val items = listOf(PinnedMessage(convSeq = 9), PinnedMessage(convSeq = 7), PinnedMessage(convSeq = 5))
        assertEquals(listOf(7L), PinnedPanel.without(items, listOf(9L, 5L, 42L)).map { it.convSeq })
        assertTrue(PinnedPanel.without(items, listOf(9L, 7L, 5L)).isEmpty())
        assertEquals(items, PinnedPanel.without(items, emptyList()))
    }

    @Test fun `定位图标：我发的在气泡左侧，别人的在右侧`() {
        assertTrue(PinnedPanel.locateIconLeading(mine = true))
        assertFalse(PinnedPanel.locateIconLeading(mine = false))
    }

    @Test fun `日期胶囊：首条必显，同日不重复，跨日再显`() {
        val day = 24L * 3600_000
        val t0 = java.util.Calendar.getInstance().apply { set(2026, 9, 14, 12, 0, 0) }.timeInMillis
        assertTrue(PinnedPanel.showsDayPill(null, t0))
        assertFalse(PinnedPanel.showsDayPill(t0, t0 + 3600_000))
        assertTrue(PinnedPanel.showsDayPill(t0, t0 - day))
    }
}
