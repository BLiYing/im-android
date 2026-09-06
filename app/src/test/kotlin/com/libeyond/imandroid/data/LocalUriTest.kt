package com.libeyond.imandroid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正文还是本地 uri」的判据。
 *
 * 这条错了不会报错：上传没走完就被杀进程时，重连后会把 `content://media/...`
 * 当消息正文发给服务端，收件人拿到一个**永远打不开的地址**，而且改不回来。
 */
class LocalUriTest {

    @Test
    fun `content 与 file 协议判为本地`() {
        assertTrue(isLocalUri("content://media/external/images/media/19"))
        assertTrue(isLocalUri("file:///storage/emulated/0/Pictures/a.png"))
    }

    @Test
    fun `服务端相对路径不是本地 uri`() {
        // 上传成功后 content 是 /uploads/xxx —— 这是要发出去的正确值
        assertFalse(isLocalUri("/uploads/req-abc__a.png"))
    }

    @Test
    fun `绝对 http 地址不是本地 uri`() {
        assertFalse(isLocalUri("https://cdn.example.com/a.png"))
    }

    @Test
    fun `普通文本不是本地 uri`() {
        assertFalse(isLocalUri("hello"))
        assertFalse(isLocalUri(""))
    }
}
