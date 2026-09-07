package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自建相册选择器的纯逻辑（Android 11 上系统 Photo Picker 不存在，见 [MediaPick] 注释）。
 *
 * 这里钉的两条最容易在重构里丢：**选中是有序的**（编号=发送顺序，取消要顺延）、
 * **相册排序按最新时间不按数量**（按数量排会把 Screenshots 顶到最前）。
 */
class MediaPickTest {

    private fun asset(
        id: Long,
        bucket: String = "b1",
        bucketName: String = "Camera",
        date: Long = 1000,
        size: Long = 1024,
    ) = MediaAsset(
        id = id,
        uri = "content://media/$id",
        mime = "image/jpeg",
        displayName = "IMG_$id.jpg",
        sizeBytes = size,
        dateAddedSec = date,
        bucketId = bucket,
        bucketName = bucketName,
    )

    // —— 选中顺序 ——

    @Test
    fun `编号就是发送顺序`() {
        var sel = listOf<Long>()
        sel = MediaPick.toggle(sel, 7)!!
        sel = MediaPick.toggle(sel, 3)!!
        sel = MediaPick.toggle(sel, 5)!!
        assertEquals(listOf(7L, 3L, 5L), sel)
        assertEquals(1, MediaPick.indexOf(sel, 7))
        assertEquals(2, MediaPick.indexOf(sel, 3))
        assertEquals(3, MediaPick.indexOf(sel, 5))
        assertEquals(0, MediaPick.indexOf(sel, 99))
    }

    @Test
    fun `取消中间一张后面顺延`() {
        val sel = listOf(1L, 2L, 3L, 4L)
        val after = MediaPick.toggle(sel, 2)!!
        assertEquals(listOf(1L, 3L, 4L), after)
        // 3 从「第 3 个」变成「第 2 个」——用 Set 存就做不到这件事
        assertEquals(2, MediaPick.indexOf(after, 3))
        assertEquals(3, MediaPick.indexOf(after, 4))
    }

    @Test
    fun `超上限返回 null 而不是静默丢弃`() {
        val full = (1L..MediaPick.LIMIT.toLong()).toList()
        assertEquals(9, MediaPick.LIMIT)
        assertNull(MediaPick.toggle(full, 100))
        // 满了以后取消仍然要能取消
        assertEquals(MediaPick.LIMIT - 1, MediaPick.toggle(full, 1)!!.size)
    }

    // —— 大小闸门 ——

    @Test
    fun `超 20MB 与零字节都不可选`() {
        assertTrue(MediaPick.selectable(asset(1, size = 20L * 1024 * 1024)))
        assertFalse(MediaPick.selectable(asset(2, size = 20L * 1024 * 1024 + 1)))
        // size=0 是 MediaStore 里的坏行（文件已删/正在写入），选了必然发失败
        assertFalse(MediaPick.selectable(asset(3, size = 0)))
    }

    // —— 分桶 ——

    @Test
    fun `全部恒在首位且计数是总数`() {
        val list = listOf(asset(1, "b1"), asset(2, "b2"), asset(3, "b2"))
        val buckets = MediaPick.buckets(list)
        assertEquals(MediaPick.ALL_BUCKET, buckets.first().id)
        assertEquals("全部", buckets.first().name)
        assertEquals(3, buckets.first().count)
    }

    @Test
    fun `相册按最新时间倒序而不是按数量`() {
        // Screenshots 有 3 张但都很旧；Camera 只有 1 张但是刚拍的 → Camera 必须排前面
        val list = listOf(
            asset(1, "shots", "Screenshots", date = 100),
            asset(2, "shots", "Screenshots", date = 101),
            asset(3, "shots", "Screenshots", date = 102),
            asset(4, "cam", "Camera", date = 999),
        )
        val real = MediaPick.buckets(list).drop(1)
        assertEquals(listOf("Camera", "Screenshots"), real.map { it.name })
        assertEquals(3, real.last().count)
    }

    @Test
    fun `封面取桶内最新的那张`() {
        val list = listOf(
            asset(1, "cam", date = 100),
            asset(2, "cam", date = 500),
            asset(3, "cam", date = 300),
        )
        val cam = MediaPick.buckets(list).first { it.id == "cam" }
        assertEquals("content://media/2", cam.coverUri)
        // 「全部」的封面同样是全局最新的那张，两处不能讲两个故事
        assertEquals("content://media/2", MediaPick.buckets(list).first().coverUri)
    }

    @Test
    fun `入参不按时间排序也要排对`() {
        // 不能依赖「调用方已经排好序」这种隐形契约——改一句 SQL 就会悄悄坏掉
        val list = listOf(asset(1, "cam", date = 100), asset(2, "cam", date = 900))
        assertEquals("content://media/2", MediaPick.buckets(list).first { it.id == "cam" }.coverUri)
    }

    @Test
    fun `桶名为空回落未命名`() {
        val b = MediaPick.buckets(listOf(asset(1, "x", bucketName = "  "))).first { it.id == "x" }
        assertEquals("未命名", b.name)
    }

    @Test
    fun `空相册不产出全部桶`() {
        assertTrue(MediaPick.buckets(emptyList()).isEmpty())
    }

    // —— 过滤与还原 ——

    @Test
    fun `按桶过滤`() {
        val list = listOf(asset(1, "a"), asset(2, "b"), asset(3, "a"))
        assertEquals(3, MediaPick.filter(list, MediaPick.ALL_BUCKET).size)
        assertEquals(listOf(1L, 3L), MediaPick.filter(list, "a").map { it.id })
    }

    @Test
    fun `还原按选中顺序而不是相册顺序`() {
        val list = listOf(asset(1), asset(2), asset(3))
        assertEquals(listOf(3L, 1L), MediaPick.ordered(list, listOf(3L, 1L)).map { it.id })
    }

    @Test
    fun `选中期间被删掉的照片跳过而不是崩`() {
        val list = listOf(asset(1), asset(3))
        assertEquals(listOf(1L, 3L), MediaPick.ordered(list, listOf(1L, 2L, 3L)).map { it.id })
    }
}
