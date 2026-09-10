package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.screens.AlbumMember
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.showsSenderAvatar
import com.libeyond.imandroid.ui.screens.showsSenderName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群内发送者头像挂在**连续段的最后一条**（IMServer `docs/UI_SPEC.md` §3，与 iOS/Web 一致）。
 *
 * 这条判据错了肉眼很难发现：段里每条都挂头像看着也"正常"，只是啰嗦。
 * 真正会被看出来的是**忘了占位**导致气泡左缘参差，而那时人多半去调 padding 了。
 */
class SenderAvatarTest {

    private fun msg(seq: Long, sender: String) = ChatRow.Confirmed(
        MessageEntity(ownerUid = "me", convId = "g1", convSeq = seq, sender = sender, content = "x"),
    )

    private val rows = listOf(
        msg(1, "alice"),
        msg(2, "alice"),   // 段中
        msg(3, "alice"),   // 段末 → 挂
        msg(4, "bob"),     // 单条成段 → 挂
        msg(5, "me"),      // 自己 → 不挂
    )

    @Test
    fun `连续段只有最后一条挂头像`() {
        assertFalse(showsSenderAvatar(rows, 0, "me", isGroup = true))
        assertFalse(showsSenderAvatar(rows, 1, "me", isGroup = true))
        assertTrue(showsSenderAvatar(rows, 2, "me", isGroup = true))
    }

    @Test
    fun `换人即为段末，单条也挂`() {
        assertTrue(showsSenderAvatar(rows, 3, "me", isGroup = true))
    }

    @Test
    fun `自己的消息不挂头像`() {
        assertFalse(showsSenderAvatar(rows, 4, "me", isGroup = true))
    }

    @Test
    fun `单聊一律不挂——挂了就是白占 48dp`() {
        rows.indices.forEach { i ->
            assertFalse("单聊第 $i 条不该挂", showsSenderAvatar(rows, i, "me", isGroup = false))
        }
    }

    @Test
    fun `最后一条消息也算段末（越界的下一行不能当成同一人）`() {
        val single = listOf(msg(1, "alice"))
        assertTrue(showsSenderAvatar(single, 0, "me", isGroup = true))
    }

    @Test
    fun `日期分隔行插在中间时，两侧仍是各自的段末`() {
        // 分隔行不是 Confirmed → 它前面那条必然是段末。
        val withDay = listOf(msg(1, "alice"), ChatRow.DayLabel(0L), msg(2, "alice"))
        assertTrue("分隔行前必为段末", showsSenderAvatar(withDay, 0, "me", isGroup = true))
        assertTrue(showsSenderAvatar(withDay, 2, "me", isGroup = true))
    }

    private fun album(seq: Long, sender: String) = ChatRow.Album(
        listOf(
            AlbumMember.Sent(
                MessageEntity(ownerUid = "me", convId = "g1", convSeq = seq, sender = sender, content = "x", contentType = "image"),
            ),
        ),
    )

    /**
     * 宫格行此前不进判据：接收端九宫格既不占头像列也不显名，左缘比同一段的文字气泡少一截，
     * 看不出是谁发的（2026-09-10 用户报的第 2 条）。
     */
    @Test
    fun `对方发的一组图与文字同段——段末挂头像、段首显名`() {
        val r = listOf(msg(1, "alice"), album(2, "alice"))
        assertFalse(showsSenderAvatar(r, 0, "me", isGroup = true))
        assertTrue("宫格是段末，该挂", showsSenderAvatar(r, 1, "me", isGroup = true))
        assertFalse("同段第二条不再显名", showsSenderName(r, 1, "me", isGroup = true))

        val solo = listOf(msg(1, "bob"), album(2, "alice"))
        assertTrue("换人成段，宫格自己就是段首", showsSenderName(solo, 1, "me", isGroup = true))
        assertTrue(showsSenderAvatar(solo, 1, "me", isGroup = true))
    }

    @Test
    fun `自己发的一组图不挂头像、单聊的一组图也不挂`() {
        assertFalse(showsSenderAvatar(listOf(album(1, "me")), 0, "me", isGroup = true))
        assertFalse(showsSenderAvatar(listOf(album(1, "alice")), 0, "me", isGroup = false))
    }

    @Test
    fun `发送者为空的消息不挂——取色种子会退化成同一个颜色`() {
        val blank = listOf(msg(1, ""))
        assertFalse(showsSenderAvatar(blank, 0, "me", isGroup = true))
    }
}
