package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 存相册的文件名 / MIME 推导。每一条都对应一种「相册里出现坏条目」的具体表现。 */
class MediaSaveNameTest {

    private val now = 1_757_000_000_000L

    @Test
    fun `取服务端命名里双下划线之后的原始名`() {
        assertEquals(
            "假期.jpg",
            MediaSaveName.fileNameFor("http://h/uploads/req-abc123__假期.jpg", false, now),
        )
    }

    /**
     * 带 `?` 的 DISPLAY_NAME 在部分 ROM 上直接插入失败——
     * 表现是「点保存没反应」，日志里只有一句 insert 返回 null。
     */
    @Test
    fun `query 与 fragment 不能进文件名`() {
        val n = MediaSaveName.fileNameFor("http://h/uploads/a.jpg?token=xyz#top", false, now)
        assertEquals("a.jpg", n)
        assertFalse(n.contains("?"))
        assertFalse(n.contains("#"))
    }

    /** `DISPLAY_NAME` 里带 `/` 会被当成子目录；前导点是隐藏文件。两者都得清掉。 */
    @Test
    fun `路径分隔与前导点都要清掉`() {
        val n = MediaSaveName.fileNameFor("http://h/uploads/req-1__..%2F..%2Fetc%2Fpasswd", false, now)
        assertFalse(n.contains("/"))
        assertFalse(n.startsWith("."))
    }

    @Test
    fun `认不出扩展名就按类型补`() {
        assertTrue(MediaSaveName.fileNameFor("http://h/uploads/req-1__noext", false, now).endsWith(".jpg"))
        assertTrue(MediaSaveName.fileNameFor("http://h/uploads/req-1__noext", true, now).endsWith(".mp4"))
    }

    @Test
    fun `完全取不出名字时用时间戳兜底`() {
        assertEquals("IM_$now.mp4", MediaSaveName.fileNameFor("http://h/uploads/", true, now))
    }

    @Test
    fun `本地 content uri 也能推出名字`() {
        val n = MediaSaveName.fileNameFor("content://media/external/video/media/30", true, now)
        assertEquals("30.mp4", n)
    }

    /**
     * **大类必须与写入的集合一致**：往 MediaStore.Images 里塞 `video/mp4` 不会报错，
     * 只会在相册里留一条点不开的条目。所以 isVideo 是权威，扩展名只挑细分。
     */
    @Test
    fun `MIME 大类由 isVideo 定，不被扩展名带偏`() {
        assertTrue(MediaSaveName.mimeFor("weird.png", true).startsWith("video/"))
        assertTrue(MediaSaveName.mimeFor("weird.mp4", false).startsWith("image/"))
    }

    @Test
    fun `扩展名在大类内部挑细分`() {
        assertEquals("image/png", MediaSaveName.mimeFor("a.PNG", false))
        assertEquals("video/quicktime", MediaSaveName.mimeFor("a.mov", true))
        assertEquals("image/jpeg", MediaSaveName.mimeFor("a.unknown", false))
        assertEquals("video/mp4", MediaSaveName.mimeFor("a.unknown", true))
    }

    @Test
    fun `超长名字截断`() {
        val long = "x".repeat(300)
        assertTrue(MediaSaveName.fileNameFor("http://h/uploads/req-1__$long", false, now).length <= 84)
    }
}
