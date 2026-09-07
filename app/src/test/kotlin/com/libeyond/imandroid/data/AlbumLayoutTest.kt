package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相册宫格布局。**逐条对齐 iOS `IMAlbumRowPattern`**——三端宫格排法不同的话，
 * 同一组图在三端裁剪出的构图都不一样，用户会以为发出去的东西被改了。
 */
class AlbumLayoutTest {

    @Test
    fun `行模式逐条对齐 iOS`() {
        assertEquals(listOf(1), AlbumLayout.rowPattern(1))
        assertEquals(listOf(2), AlbumLayout.rowPattern(2))
        // 3 是 [1,2] 不是 [2,1]：大图在上、两张小的在下（Telegram 构图）
        assertEquals(listOf(1, 2), AlbumLayout.rowPattern(3))
        assertEquals(listOf(2, 2), AlbumLayout.rowPattern(4))
        assertEquals(listOf(2, 3), AlbumLayout.rowPattern(5))
        assertEquals(listOf(3, 3), AlbumLayout.rowPattern(6))
        assertEquals(listOf(1, 3, 3), AlbumLayout.rowPattern(7))
        assertEquals(listOf(2, 3, 3), AlbumLayout.rowPattern(8))
        assertEquals(listOf(3, 3, 3), AlbumLayout.rowPattern(9))
    }

    @Test
    fun `超过 9 张按 9 排——选图器上限就是 9`() {
        assertEquals(listOf(3, 3, 3), AlbumLayout.rowPattern(12))
        assertEquals(9, AlbumLayout.MAX)
    }

    @Test
    fun `0 张没有行`() {
        assertEquals(emptyList<Int>(), AlbumLayout.rowPattern(0))
        assertEquals(0f, AlbumLayout.heightFor(0), 0.01f)
    }

    @Test
    fun `每行格子把宽度减去间隙后均分；单列那行固定 150`() {
        assertEquals(150f, AlbumLayout.tileSize(1), 0.01f)
        assertEquals((240f - 2f) / 2f, AlbumLayout.tileSize(2), 0.01f)
        assertEquals((240f - 4f) / 3f, AlbumLayout.tileSize(3), 0.01f)
    }

    @Test
    fun `总高等于各行高加间隙，末尾不多一个间隙`() {
        // 4 张 = [2,2]：两行各 119，中间一个 2 的间隙
        val two = AlbumLayout.tileSize(2)
        assertEquals(two * 2 + AlbumLayout.GAP, AlbumLayout.heightFor(4), 0.01f)
        // 1 张 = 单行 150，没有间隙
        assertEquals(150f, AlbumLayout.heightFor(1), 0.01f)
    }

    @Test
    fun `只有图片和视频进宫格——同组混进文件时那几条要单独显示`() {
        assertTrue(AlbumLayout.isAlbumMember(ContentType.IMAGE, "g1"))
        assertTrue(AlbumLayout.isAlbumMember(ContentType.VIDEO, "g1"))
        // 服务端只透传 group_id 不校验类型，端上不能假定同组必然同型
        assertFalse(AlbumLayout.isAlbumMember(ContentType.FILE, "g1"))
        assertFalse(AlbumLayout.isAlbumMember(ContentType.VOICE, "g1"))
        assertFalse(AlbumLayout.isAlbumMember(ContentType.TEXT, "g1"))
        // 没有 group_id 的图片是普通单图，不是一格宫格
        assertFalse(AlbumLayout.isAlbumMember(ContentType.IMAGE, null))
        assertFalse(AlbumLayout.isAlbumMember(ContentType.IMAGE, ""))
    }
}

/**
 * 相册聚簇（`buildChatRows` 里那段）。判据比布局更容易出错，逐条钉住。
 */
class AlbumClusterTest {

    private fun img(seq: Long, gid: String?, ts: Long = seq, recalled: Long? = null) =
        com.libeyond.imandroid.data.db.MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = seq, sender = "u1",
            contentType = ContentType.IMAGE, content = "/a$seq.jpg",
            groupId = gid, timestamp = ts, recalledAt = recalled,
        )

    private fun text(seq: Long, ts: Long = seq) =
        com.libeyond.imandroid.data.db.MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = seq, sender = "u1",
            contentType = ContentType.TEXT, content = "hi", timestamp = ts,
        )

    private fun rows(vararg m: com.libeyond.imandroid.data.db.MessageEntity) =
        com.libeyond.imandroid.ui.screens.buildChatRows(m.toList(), emptyList())
            .filterNot { it is com.libeyond.imandroid.ui.screens.ChatRow.DayLabel }

    @Test
    fun `同组连续多图聚成一格宫格`() {
        val r = rows(img(1, "g"), img(2, "g"), img(3, "g"))
        assertEquals(1, r.size)
        val a = r[0] as com.libeyond.imandroid.ui.screens.ChatRow.Album
        assertEquals(3, a.msgs.size)
    }

    @Test
    fun `只有一张的组不画宫格——那就是一张普通图`() {
        val r = rows(img(1, "g"))
        assertTrue(r[0] is com.libeyond.imandroid.ui.screens.ChatRow.Confirmed)
    }

    @Test
    fun `中间隔了别的消息就不是一批发的，不能硬并`() {
        // 硬并会把时间顺序搅乱：文本本该夹在两张图中间
        val r = rows(img(1, "g"), text(2), img(3, "g"))
        assertEquals(3, r.size)
        assertTrue(r.none { it is com.libeyond.imandroid.ui.screens.ChatRow.Album })
    }

    @Test
    fun `不同组不并`() {
        val r = rows(img(1, "g1"), img(2, "g2"))
        assertEquals(2, r.size)
        assertTrue(r.none { it is com.libeyond.imandroid.ui.screens.ChatRow.Album })
    }

    @Test
    fun `撤回的成员退出宫格、单独显示墓碑`() {
        val r = rows(img(1, "g"), img(2, "g", recalled = 1L), img(3, "g"))
        // 撤回那条把组切成两段，每段各只剩一张 → 三条都是普通气泡
        assertEquals(3, r.size)
        assertTrue(r.none { it is com.libeyond.imandroid.ui.screens.ChatRow.Album })
    }

    @Test
    fun `超过 9 张只画前 9 张`() {
        val many = (1L..12L).map { img(it, "g") }.toTypedArray()
        val a = rows(*many)[0] as com.libeyond.imandroid.ui.screens.ChatRow.Album
        assertEquals(AlbumLayout.MAX, a.msgs.size)
    }

    @Test
    fun `没有 group_id 的多张图各自独立`() {
        val r = rows(img(1, null), img(2, null))
        assertEquals(2, r.size)
        assertTrue(r.none { it is com.libeyond.imandroid.ui.screens.ChatRow.Album })
    }
}
