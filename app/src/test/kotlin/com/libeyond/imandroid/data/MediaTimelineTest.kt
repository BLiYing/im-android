package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器左右翻页的判据（[MediaTimeline]）。
 *
 * ### 为什么这一族必须有单测
 * 错法**全是静默的**：下标算错只是"翻到了另一张图"，续拉重复只是"同一张出现两次"，
 * 都不报错、截图也看不出。三端已经为同一族判据各栽过一次——
 * 重复页（im-web 成员分页 / 归档分页）、拼到前面不挪下标（会话内搜索翻页）、
 * 拿 clientMsgId 当身份（im-web 查看器点最后一张跳第一张）。
 */
class MediaTimelineTest {

    private fun media(seq: Long, type: String = ContentType.IMAGE) =
        ViewerMedia(convSeq = seq, contentType = type, content = "/uploads/$seq.jpg")

    // ————————————————— 谁进序列 —————————————————

    @Test
    fun `只有图片和视频进序列`() {
        assertTrue(MediaTimeline.viewable(ContentType.IMAGE, "/u/a.jpg", 1, recalled = false))
        assertTrue(MediaTimeline.viewable(ContentType.VIDEO, "/u/a.mp4", 1, recalled = false))
        assertFalse(MediaTimeline.viewable(ContentType.FILE, "/u/a.pdf", 1, recalled = false))
        assertFalse(MediaTimeline.viewable(ContentType.TEXT, "你好", 1, recalled = false))
    }

    @Test
    fun `撤回的和内容为空的不进序列`() {
        assertFalse(MediaTimeline.viewable(ContentType.IMAGE, "/u/a.jpg", 1, recalled = true))
        assertFalse(MediaTimeline.viewable(ContentType.IMAGE, "", 1, recalled = false))
    }

    @Test
    fun `没有 conv_seq 的不进序列——序列全靠它定位与比新旧`() {
        assertFalse(MediaTimeline.viewable(ContentType.IMAGE, "/u/a.jpg", 0, recalled = false))
    }

    // ————————————————— 定位 —————————————————

    @Test
    fun `按 conv_seq 定位`() {
        val items = listOf(media(10), media(20), media(30))
        assertEquals(1, MediaTimeline.indexOf(items, 20))
        assertEquals(-1, MediaTimeline.indexOf(items, 25))
    }

    @Test
    fun `未确认的消息定位不到——退化成只看这一条`() {
        // im-web 的原样事故是拿 clientMsgId 当身份，入站消息恒空 → 一律命中第一条。
        // 本端根本不让没有 conv_seq 的进序列，所以这里要的是"找不到"，不是"找到第 0 条"。
        assertEquals(-1, MediaTimeline.indexOf(listOf(media(10)), 0))
    }

    // 「越界即停」不在这里测：本端交给 HorizontalPager 的 pageCount 保证（见 MediaTimeline 的注释）。

    // ————————————————— 续拉更早 —————————————————

    @Test
    fun `快到最旧一端才去续拉`() {
        assertTrue(MediaTimeline.wantsOlder(index = 1, count = 20, hasMore = true, loading = false))
        assertFalse(MediaTimeline.wantsOlder(index = 9, count = 20, hasMore = true, loading = false))
    }

    @Test
    fun `没有更早的就不问`() {
        assertFalse(MediaTimeline.wantsOlder(index = 0, count = 20, hasMore = false, loading = false))
    }

    @Test
    fun `在途时不再问——不守这条会把同一页追加两次`() {
        assertFalse(MediaTimeline.wantsOlder(index = 0, count = 20, hasMore = true, loading = true))
    }

    // ————————————————— 拼更早的一页 —————————————————

    @Test
    fun `更早的一页拼到前面，下标要按 added 往后挪`() {
        val current = listOf(media(20), media(30))
        val r = MediaTimeline.prependOlder(current, listOf(media(15), media(10)))
        assertEquals(listOf(10L, 15L, 20L, 30L), r.items.map { it.convSeq })
        assertEquals(2, r.added)
        // 原先看的是第 0 张（seq=20），拼完它跑到了下标 2——不 += added 就会当场跳到 seq=10
        assertEquals(2, 0 + r.added)
        assertEquals(20L, r.items[2].convSeq)
    }

    @Test
    fun `重复页不再塞一遍`() {
        val current = listOf(media(20), media(30))
        val r = MediaTimeline.prependOlder(current, listOf(media(30), media(20), media(18)))
        assertEquals(listOf(18L, 20L, 30L), r.items.map { it.convSeq })
        assertEquals(1, r.added)
    }

    @Test
    fun `只收比当前最旧还旧的——游标回退带回的中间项也不收`() {
        val current = listOf(media(20), media(30))
        val r = MediaTimeline.prependOlder(current, listOf(media(25)))
        assertEquals(listOf(20L, 30L), r.items.map { it.convSeq })
        assertEquals(0, r.added)
    }

    @Test
    fun `空页原样返回`() {
        val current = listOf(media(20))
        val r = MediaTimeline.prependOlder(current, emptyList())
        assertEquals(current, r.items)
        assertEquals(0, r.added)
    }

    @Test
    fun `当前为空时整页都收并按升序排好`() {
        val r = MediaTimeline.prependOlder(emptyList(), listOf(media(30), media(10), media(20)))
        assertEquals(listOf(10L, 20L, 30L), r.items.map { it.convSeq })
        assertEquals(3, r.added)
    }

    // ————————————————— 两种来源的适配 —————————————————

    @Test
    fun `本地消息与服务端归档项转出来的序列项一致`() {
        val fromLocal = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 7, sender = "u1",
            contentType = ContentType.VIDEO, content = "/uploads/a.mp4", poster = "/uploads/a.jpg",
        ).toViewerMedia()
        val fromServer = ConvMediaItem(
            convSeq = 7, sender = "u1", contentType = ContentType.VIDEO,
            content = "/uploads/a.mp4", poster = "/uploads/a.jpg",
        ).toViewerMedia()
        assertEquals(fromServer, fromLocal)
        assertTrue(fromLocal!!.isVideo)
    }

    @Test
    fun `本地撤回与为所有人删除的都转不出序列项`() {
        val base = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 7,
            contentType = ContentType.IMAGE, content = "/uploads/a.jpg",
        )
        assertNull(base.copy(recalledAt = 1).toViewerMedia())
        assertNull(base.copy(deletedAt = 1).toViewerMedia())
        assertNull(base.copy(convSeq = 0).toViewerMedia())
    }

    @Test
    fun `本地缺 poster 时转成空串，与服务端那侧对齐`() {
        // MessageEntity 的 poster 是 null，ConvMediaItem 那侧 JSON 不带就是 ""。
        // 不归一的话同一条消息从两个入口打开，一个有封面一个没有
        val m = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 7,
            contentType = ContentType.IMAGE, content = "/uploads/a.jpg",
        ).toViewerMedia()
        assertEquals("", m!!.poster)
    }
}
