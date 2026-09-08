package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 补种缩略的**筛选判据**。真正的编码要 android.graphics，这里只钉住"该补哪些"——
 * 而"该补哪些"正是会出事的地方：漏筛一条就是每次消息列表变化都去读一遍磁盘。
 */
class ThumbBackfillFilterTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "tb-" + System.nanoTime())
    private val cache = MediaCache(root)

    private fun msg(seq: Long, type: String = ContentType.IMAGE, thumb: String? = null, url: String = "/uploads/$seq.jpg") =
        MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = seq, sender = "u1",
            contentType = type, content = url, timestamp = seq, thumb = thumb,
        )

    /** 与 [ThumbBackfill] 里那段 filter 链同口径的可测版本。 */
    private fun candidates(msgs: List<MessageEntity>, tried: Set<String> = emptySet()) =
        msgs.filter { it.contentType == ContentType.IMAGE }
            .filter { it.thumb.isNullOrBlank() }
            .filter { it.convSeq > 0 }
            .filter { "c1#${it.convSeq}" !in tried }
            .filter { cache.isReady(it.content) }

    @Test
    fun `只补图片、只补缺缩略的、只补原图已在本地的`() {
        cache.fileFor("/uploads/1.jpg").writeBytes(ByteArray(10))
        cache.fileFor("/uploads/2.jpg").writeBytes(ByteArray(10))
        // 3 没下载；4 是视频；5 已经有缩略了
        val list = listOf(
            msg(1),
            msg(2, thumb = "data:image/jpeg;base64,AAA"),
            msg(3),
            msg(4, type = ContentType.VIDEO),
            msg(5),
        )
        assertEquals(listOf(1L), candidates(list).map { it.convSeq })
    }

    @Test
    fun `待发消息不补——它还没有 conv_seq`() {
        cache.fileFor("/uploads/0.jpg").writeBytes(ByteArray(10))
        assertTrue(candidates(listOf(msg(0, url = "/uploads/0.jpg"))).isEmpty())
    }

    @Test
    fun `试过一次就不再试`() {
        cache.fileFor("/uploads/7.jpg").writeBytes(ByteArray(10))
        val list = listOf(msg(7, url = "/uploads/7.jpg"))
        assertEquals(1, candidates(list).size)
        // 解不出来的（坏文件/非图片）再试一百次也一样，而这段挂在"列表每次变化"上
        assertTrue(candidates(list, tried = setOf("c1#7")).isEmpty())
    }
}
