package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.ui.screens.AlbumMember
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.showsSenderAvatar
import com.libeyond.imandroid.ui.screens.showsSenderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊昵称只挂段首、头像只挂段末（十七条对齐 #15，iOS `isFirstInSenderRun:`/`isLastInSenderRun:`）。
 * [SenderAvatarTest] 钉的是头像的老用例；这里补昵称，以及两者共用的断段规则。
 */
class SenderRunRowsTest {

    private fun msg(seq: Long, sender: String, type: String = "text", recalled: Long? = null) =
        ChatRow.Confirmed(
            MessageEntity(
                ownerUid = "me", convId = "g1", convSeq = seq, sender = sender,
                contentType = type, content = "x", recalledAt = recalled,
            ),
        )

    private fun names(rows: List<ChatRow>) = rows.indices.map { showsSenderName(rows, it, "me", isGroup = true) }
    private fun avatars(rows: List<ChatRow>) = rows.indices.map { showsSenderAvatar(rows, it, "me", isGroup = true) }

    @Test
    fun `连发三条只有第一条显昵称`() {
        val rows = listOf(msg(1, "alice"), msg(2, "alice"), msg(3, "alice"), msg(4, "bob"))
        assertEquals(listOf(true, false, false, true), names(rows))
    }

    @Test
    fun `自己的消息与单聊不显昵称`() {
        val rows = listOf(msg(1, "me"), msg(2, "alice"))
        assertFalse(showsSenderName(rows, 0, "me", isGroup = true))
        assertFalse(showsSenderName(rows, 1, "me", isGroup = false))
    }

    @Test
    fun `新消息分割线不拆段`() {
        val rows = listOf(msg(1, "alice"), ChatRow.UnreadDivider, msg(2, "alice"))
        assertEquals(listOf(true, false, false), names(rows))
        assertEquals(listOf(false, false, true), avatars(rows))
    }

    @Test
    fun `日期行拆段`() {
        val rows = listOf(msg(1, "alice"), ChatRow.DayLabel(0L), msg(2, "alice"))
        assertEquals(listOf(true, false, true), names(rows))
    }

    @Test
    fun `夹一条系统消息就断段——两侧各自有名字和头像`() {
        val rows = listOf(msg(1, "alice"), msg(2, "alice", type = "system"), msg(3, "alice"))
        assertEquals(listOf(true, false, true), names(rows))
        assertEquals(listOf(true, false, true), avatars(rows))
    }

    @Test
    fun `撤回的消息自成一段`() {
        val rows = listOf(msg(1, "alice", recalled = 5L), msg(2, "alice"))
        assertEquals(listOf(true, true), names(rows))
        assertEquals(listOf(true, true), avatars(rows))
    }

    @Test
    fun `同一人的相册行算在段里`() {
        val album = ChatRow.Album(
            listOf(AlbumMember.Sent(msg(1, "alice").msg), AlbumMember.Sent(msg(2, "alice").msg)),
        )
        val rows = listOf(album, msg(3, "alice"))
        assertFalse("前面是她的相册，不再显名字", showsSenderName(rows, 1, "me", isGroup = true))
    }

    @Test
    fun `后面紧跟我的待发消息时，对方这条是段末`() {
        val pending = ChatRow.Pending(
            PendingMessageEntity(ownerUid = "me", clientMsgId = "c1", convId = "g1", to = "g1", contentType = "text", content = "y"),
        )
        val rows = listOf(msg(1, "alice"), pending)
        assertTrue(showsSenderAvatar(rows, 0, "me", isGroup = true))
    }
}
