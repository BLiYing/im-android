package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.showsSenderAvatar
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

    @Test
    fun `发送者为空的消息不挂——取色种子会退化成同一个颜色`() {
        val blank = listOf(msg(1, ""))
        assertFalse(showsSenderAvatar(blank, 0, "me", isGroup = true))
    }
}
