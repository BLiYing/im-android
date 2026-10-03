package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.PinnedMessage
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 置顶 / 公告 / 入群申请横幅的纯判据（对齐 iOS `canPinMessages` / `IMChatBannerStack`）。 */
class PinnedBannerTest {
    private fun msg(seq: Long = 10, type: String = ContentType.TEXT, pinnedAt: Long? = null, recalledAt: Long? = null) =
        MessageEntity(
            ownerUid = "1", convId = "g", convSeq = seq, sender = "2", contentType = type, content = "x",
            timestamp = 1, pinnedAt = pinnedAt, recalledAt = recalledAt,
        )

    private fun pin(seq: Long) = PinnedMessage(convSeq = seq)

    @Test fun `单聊双方都能置顶`() = assertTrue(PinnedBanner.canPin(false, true, "member"))

    @Test fun `群聊 perm_pin 开着只有群主管理员`() {
        assertFalse(PinnedBanner.canPin(true, true, "member"))
        assertTrue(PinnedBanner.canPin(true, true, "admin"))
        assertTrue(PinnedBanner.canPin(true, true, "owner"))
    }

    @Test fun `群聊 perm_pin 关着所有成员都能`() = assertTrue(PinnedBanner.canPin(true, false, "member"))

    @Test fun `没置顶的普通消息给置顶，已置顶的给取消`() {
        assertEquals(MessageAction.Pin, PinnedBanner.pinAction(msg(), true))
        assertEquals(MessageAction.Unpin, PinnedBanner.pinAction(msg(pinnedAt = 5), true))
    }

    @Test fun `已撤回的置顶消息仍能取消置顶，未置顶的撤回消息不能置顶`() {
        assertEquals(MessageAction.Unpin, PinnedBanner.pinAction(msg(pinnedAt = 5, recalledAt = 9), true))
        assertNull(PinnedBanner.pinAction(msg(recalledAt = 9), true))
    }

    @Test fun `系统消息不能置顶，没有 conv_seq 的待发行不能置顶，没权限不给`() {
        assertNull(PinnedBanner.pinAction(msg(type = ContentType.SYSTEM), true))
        assertNull(PinnedBanner.pinAction(msg(seq = 0), true))
        assertNull(PinnedBanner.pinAction(msg(), false))
    }

    @Test fun `长按菜单：撤回墓碑上只剩取消置顶`() {
        val recalledPinned = msg(pinnedAt = 5, recalledAt = 9)
        assertEquals(
            listOf(MessageAction.Unpin),
            MessageActions.availableFor(recalledPinned, "1", true, false, canPin = true),
        )
        assertTrue(MessageActions.availableFor(msg(recalledAt = 9), "1", true, false, canPin = true).isEmpty())
    }

    @Test fun `长按菜单：有权限才出置顶项`() {
        assertTrue(MessageAction.Pin in MessageActions.availableFor(msg(), "1", true, false, canPin = true))
        assertFalse(MessageAction.Pin in MessageActions.availableFor(msg(), "1", true, false, canPin = false))
    }

    @Test fun `收起签名随条数和最新一条变化，内容一变就重新出现`() {
        val sig = PinnedBanner.pinSignature(listOf(pin(9), pin(5)))
        assertEquals("2:9", sig)
        assertTrue(PinnedBanner.dismissed(sig, "2:9"))
        assertFalse("别人又置顶一条", PinnedBanner.dismissed(PinnedBanner.pinSignature(listOf(pin(11), pin(9), pin(5))), "2:9"))
        assertFalse("取消置顶最新一条", PinnedBanner.dismissed(PinnedBanner.pinSignature(listOf(pin(5))), "2:9"))
        assertFalse("没收起过", PinnedBanner.dismissed(sig, null))
        assertFalse("空集合不算收起", PinnedBanner.dismissed("", ""))
    }

    @Test fun `轮播下标夹取与循环推进`() {
        assertEquals(0, PinnedBanner.clampIndex(3, 2)) // 别人取消置顶缩短了列表
        assertEquals(0, PinnedBanner.clampIndex(-1, 2))
        assertEquals(1, PinnedBanner.clampIndex(1, 2))
        assertEquals(1, PinnedBanner.nextIndex(0, 3))
        assertEquals(0, PinnedBanner.nextIndex(2, 3))
        assertEquals(0, PinnedBanner.nextIndex(0, 0))
    }

    @Test fun `发送者标签：单聊不显，群聊昵称优先退 uid`() {
        val a = PinnedMessage(sender = "2", fromNickname = "小刚")
        assertEquals("", PinnedBanner.senderLabel(a, false))
        assertEquals("小刚", PinnedBanner.senderLabel(a, true))
        assertEquals("2", PinnedBanner.senderLabel(PinnedMessage(sender = "2"), true))
    }

    @Test fun `空白与换行折叠成一个空格`() = assertEquals("a b c", PinnedBanner.oneLine("  a\n\n b\t c "))

    private fun info(role: String, pending: Int = 0, ann: String = "", at: Long = 0, by: String = "") =
        GroupInfo(convId = "g", myRole = role, pendingCount = pending, announcement = ann, announcementAt = at, announcementBy = by)

    @Test fun `入群申请横幅只对管理层且有待审时出现`() {
        assertEquals(3, PinnedBanner.approvalCount(info("admin", 3)))
        assertEquals(0, PinnedBanner.approvalCount(info("member", 3)))
        assertEquals(0, PinnedBanner.approvalCount(null))
    }

    @Test fun `公告自动弹：有新版本才弹，自己发布的不弹，旧版本不弹`() {
        assertTrue(PinnedBanner.shouldAutoPopAnnouncement(info("member", ann = "a", at = 5, by = "9"), "1", 4))
        assertFalse("已看过这一版", PinnedBanner.shouldAutoPopAnnouncement(info("member", ann = "a", at = 5, by = "9"), "1", 5))
        assertFalse("我自己发布的", PinnedBanner.shouldAutoPopAnnouncement(info("owner", ann = "a", at = 5, by = "1"), "1", 0))
        assertFalse("没有公告", PinnedBanner.shouldAutoPopAnnouncement(info("member", at = 5), "1", 0))
        assertFalse("没有发布时间", PinnedBanner.shouldAutoPopAnnouncement(info("member", ann = "a"), "1", 0))
    }

    @Test fun `公告收起签名：同正文同签名，改一个字就变，空公告为空串`() {
        assertEquals(PinnedBanner.announcementSignature("周五开会"), PinnedBanner.announcementSignature("周五开会"))
        assertFalse(PinnedBanner.announcementSignature("周五开会") == PinnedBanner.announcementSignature("周六开会"))
        assertEquals("", PinnedBanner.announcementSignature("  "))
        assertEquals("", PinnedBanner.announcementSignature(null))
    }

    @Test fun `目标已撤回判定`() {
        assertTrue(PinnedBanner.targetRecalled(msg(recalledAt = 9)))
        assertFalse(PinnedBanner.targetRecalled(msg()))
        assertFalse("本地没有这一行就当没撤回，让跳转去开窗", PinnedBanner.targetRecalled(null))
    }
}
