package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlTest {

    @Test
    fun `相对路径按当前 host 补全`() {
        assertEquals(
            "http://10.0.2.2:8080/uploads/a.jpg",
            MediaUrl.absolute("/uploads/a.jpg", "10.0.2.2:8080", useTls = false),
        )
        assertEquals(
            "https://im.example.com/uploads/a.jpg",
            MediaUrl.absolute("/uploads/a.jpg", "im.example.com", useTls = true),
        )
    }

    /** 已是绝对地址或 data URI 的原样用——再拼一次就成了 http://host/http://... */
    @Test
    fun `绝对地址与 data URI 原样返回`() {
        assertEquals("https://x.com/a.png", MediaUrl.absolute("https://x.com/a.png", "h", false))
        assertEquals("data:image/png;base64,AAA", MediaUrl.absolute("data:image/png;base64,AAA", "h", false))
    }

    @Test
    fun `空串不拼出裸 host`() {
        assertEquals("", MediaUrl.absolute("", "10.0.2.2:8080", false))
    }

    /** 服务端命名是 `req-<id>__<原始名>`，双下划线后才是给人看的名字。 */
    @Test
    fun `从服务端文件名里取原始名`() {
        assertEquals(
            "季度财报.xlsx",
            MediaUrl.displayFileName("/uploads/req-89330c92781f4d01__季度财报.xlsx"),
        )
        // 有 file_name 字段时优先用它
        assertEquals("报表.pdf", MediaUrl.displayFileName("/uploads/req-x__y.pdf", "报表.pdf"))
        // 没有双下划线时退回整个文件名
        assertEquals("plain.png", MediaUrl.displayFileName("/uploads/plain.png"))
    }

    @Test
    fun `字节格式化`() {
        assertEquals("", MediaUrl.formatSize(0))
        assertEquals("512 B", MediaUrl.formatSize(512))
        assertEquals("1.0 KB", MediaUrl.formatSize(1024))
        assertEquals("7.0 MB", MediaUrl.formatSize(7 * 1024 * 1024))
        assertEquals("2.00 GB", MediaUrl.formatSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `时长格式化`() {
        assertEquals("0:00", MediaUrl.formatDuration(null))
        assertEquals("0:12", MediaUrl.formatDuration(12_000))
        assertEquals("1:05", MediaUrl.formatDuration(65_000))
        assertEquals("0:00", MediaUrl.formatDuration(-1))
    }
}
