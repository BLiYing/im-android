package com.libeyond.imandroid.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FcmPayload.parse]：FCM `data` payload（string-string map，`RemoteMessage.getData()` 的原生形状）
 * 解析成展示内容——服务端字段：`title`/`body`/`conv_id`/`conv_seq`/`badge`（PUSH_M5_DESIGN §3.2）。
 */
class FcmPayloadTest {

    @Test
    fun `完整 payload 全字段解出`() {
        val content = FcmPayload.parse(
            mapOf(
                "title" to "张三",
                "body" to "在吗",
                "conv_id" to "u_1002_u_2005",
                "conv_seq" to "42",
                "badge" to "3",
            ),
        )
        assertEquals(
            FcmNotificationContent(convId = "u_1002_u_2005", title = "张三", body = "在吗", convSeq = 42L, badge = 3),
            content,
        )
    }

    @Test
    fun `conv_id 缺失——返回 null，不展示`() {
        assertNull(FcmPayload.parse(mapOf("title" to "张三", "body" to "在吗")))
    }

    @Test
    fun `conv_id 空白——同样视为缺失`() {
        assertNull(FcmPayload.parse(mapOf("conv_id" to "   ")))
    }

    @Test
    fun `title body 缺失时回落空字符串，不抛异常`() {
        val content = FcmPayload.parse(mapOf("conv_id" to "g_1"))
        assertEquals("g_1", content?.convId)
        assertEquals("", content?.title)
        assertEquals("", content?.body)
        assertNull(content?.convSeq)
        assertNull(content?.badge)
    }

    @Test
    fun `conv_seq badge 非法数字字符串——回落 null，不崩`() {
        val content = FcmPayload.parse(mapOf("conv_id" to "g_1", "conv_seq" to "not_a_number", "badge" to "abc"))
        assertNull(content?.convSeq)
        assertNull(content?.badge)
    }
}
