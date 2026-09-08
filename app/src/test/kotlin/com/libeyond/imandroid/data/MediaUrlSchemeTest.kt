package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `MediaUrl.absolute` 的 **scheme 直通**规则。
 *
 * 这条判据此前散在调用点上各挡一次（`MediaViewerScreen` / `VideoPlayer` 有，
 * `AlbumBubble` 没有），于是待发宫格那几格拿到的是
 * `http://10.0.2.2:8080/content://media/...`——谁也加载不了，表现是「选完图宫格是空的」。
 * 收进函数里 + 钉住。
 */
class MediaUrlSchemeTest {

    private val host = "10.0.2.2:8080"

    @Test
    fun `相对路径补 host`() {
        assertEquals("http://$host/uploads/a.jpg", MediaUrl.absolute("/uploads/a.jpg", host, false))
        assertEquals("https://$host/uploads/a.jpg", MediaUrl.absolute("/uploads/a.jpg", host, true))
    }

    @Test
    fun `不带前导斜杠也补得对`() {
        assertEquals("http://$host/uploads/a.jpg", MediaUrl.absolute("uploads/a.jpg", host, false))
    }

    @Test
    fun `已经带 scheme 的一律原样返回`() {
        for (u in listOf(
            "http://x.com/a.jpg",
            "https://x.com/a.jpg",
            "data:image/jpeg;base64,AAA",
            // 待发消息的正文：相册的本地 URI
            "content://media/external/images/media/30",
            // 已下载媒体：沙盒里的文件
            "file:///data/user/0/com.libeyond.imandroid/files/media/ab12.jpg",
        )) {
            assertEquals(u, MediaUrl.absolute(u, host, false))
        }
    }

    @Test
    fun `空串还是空串——不要拼出一个只有 host 的地址`() {
        assertEquals("", MediaUrl.absolute("", host, false))
    }
}
