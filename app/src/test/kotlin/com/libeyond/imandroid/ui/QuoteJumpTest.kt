package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.buildChatRows
import com.libeyond.imandroid.ui.screens.originalOf
import com.libeyond.imandroid.ui.screens.rowIndexOfSeq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「点引用块跳原消息」与「引用块里的真缩略」共用同一次**本地反查**——
 * 引用快照是发送时冻结的一串文字，既不带 thumb 也不带位置，两样都只能从本地那条原消息来。
 */
class QuoteJumpTest {

    private fun msg(seq: Long, gid: String? = null, thumb: String? = null) = MessageEntity(
        ownerUid = "me", convId = "c1", convSeq = seq, sender = "u1",
        contentType = if (gid == null) ContentType.TEXT else ContentType.IMAGE,
        content = "x$seq", groupId = gid, timestamp = seq, thumb = thumb,
    )

    private fun rows(vararg m: MessageEntity) =
        buildChatRows(m.toList(), emptyList()).filterNot { it is ChatRow.DayLabel }

    @Test
    fun `反查到原消息就能拿到它的缩略`() {
        val r = rows(msg(1, "g", "data:image/jpeg;base64,AAA"), msg(2, "g"), msg(3))
        assertEquals("data:image/jpeg;base64,AAA", originalOf(r, 1)?.thumb)
    }

    @Test
    fun `宫格里的某一格也反查得到`() {
        // 组成员被聚簇进 Album 行，按 Confirmed 找会漏——而被引用的往往正是宫格里的某一张
        val r = rows(msg(1, "g"), msg(2, "g"), msg(3, "g"))
        assertEquals(1, r.size)
        assertNotNull(originalOf(r, 2))
        assertEquals(2L, originalOf(r, 2)?.convSeq)
    }

    @Test
    fun `翻不到那么早就是 null，不能瞎给一个`() {
        val r = rows(msg(5), msg(6))
        assertNull(originalOf(r, 1))
        assertEquals(-1, rowIndexOfSeq(r, 1))
    }

    @Test
    fun `非法 seq 一律 null`() {
        val r = rows(msg(1))
        assertNull(originalOf(r, 0))
        assertNull(originalOf(r, -1))
        assertEquals(-1, rowIndexOfSeq(r, 0))
    }

    @Test
    fun `跳转下标指向那一格所在的整行`() {
        // 宫格里的一格没有自己的行，跳转必须落在宫格那一行上
        val r = rows(msg(1), msg(2, "g"), msg(3, "g"))
        assertEquals(0, rowIndexOfSeq(r, 1))
        assertEquals(1, rowIndexOfSeq(r, 2))
        assertEquals(1, rowIndexOfSeq(r, 3))
    }
}
