package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaCacheTest {

    private fun tmp(): File = File(System.getProperty("java.io.tmpdir"), "mc-" + System.nanoTime())

    @Test
    fun `扩展名从相对地址里取，忽略 query 与 fragment`() {
        assertEquals("jpg", MediaCache.extensionOf("/uploads/req-1__a.jpg"))
        assertEquals("mp4", MediaCache.extensionOf("/uploads/x.mp4?token=abc"))
        assertEquals("png", MediaCache.extensionOf("/uploads/x.PNG#frag"))
    }

    @Test
    fun `取不到扩展名时不瞎猜`() {
        assertNull(MediaCache.extensionOf("/uploads/noext"))
        assertNull(MediaCache.extensionOf("/uploads/trailing."))
        // 「点后面一长串」不是扩展名（服务端文件名里有点是常事）
        assertNull(MediaCache.extensionOf("/uploads/a.verylongthing"))
    }

    @Test
    fun `同名不同内容的文件不会互相覆盖`() {
        val c = MediaCache(tmp())
        // 两条消息都叫 IMG_0001.jpg，但服务端地址不同 —— 落点必须不同
        val a = c.fileFor("/uploads/req-1__IMG_0001.jpg")
        val b = c.fileFor("/uploads/req-2__IMG_0001.jpg")
        assertFalse(a.absolutePath == b.absolutePath)
    }

    @Test
    fun `半截文件不算就绪`() {
        val root = tmp()
        val c = MediaCache(root)
        val url = "/uploads/x.jpg"
        assertFalse(c.isReady(url))
        // .part 存在也不算 —— 下完改名才算，不然半截文件会被当成图渲染
        c.partFor(url).writeBytes(ByteArray(10))
        assertFalse(c.isReady(url))
        c.fileFor(url).writeBytes(ByteArray(10))
        assertTrue(c.isReady(url))
    }

    @Test
    fun `零字节文件不算就绪`() {
        val c = MediaCache(tmp())
        val url = "/uploads/empty.jpg"
        c.fileFor(url).writeBytes(ByteArray(0))
        assertFalse(c.isReady(url))
    }

    @Test
    fun `空地址不算就绪`() {
        assertFalse(MediaCache(tmp()).isReady(""))
    }

    @Test
    fun `总字节与清理`() {
        val c = MediaCache(tmp())
        c.fileFor("/uploads/a.jpg").writeBytes(ByteArray(100))
        c.fileFor("/uploads/b.jpg").writeBytes(ByteArray(50))
        assertEquals(150L, c.totalBytes())
        c.clear()
        assertEquals(0L, c.totalBytes())
    }
}
