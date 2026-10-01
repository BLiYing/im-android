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

    @Test
    fun `type=retract——解成收回，不是新消息`() {
        val content = FcmPayload.parse(
            mapOf("type" to "retract", "retract" to "recall", "conv_id" to "u_1002_u_2005", "conv_seq" to "42", "body" to "对方撤回了一条消息"),
        )
        assertEquals(true, content?.retract)
        assertEquals(42L, content?.convSeq)
    }

    @Test
    fun `没有 type 或不认识的 type——按普通消息展示`() {
        for (data in listOf(mapOf("conv_id" to "c"), mapOf("conv_id" to "c", "type" to "something_new"))) {
            val content = FcmPayload.parse(data)
            assertEquals(false, content?.retract)
            assertEquals(false, content?.clear)
        }
    }

    @Test
    fun `type=clear——解成已读清通知，conv_seq 是已读位点`() {
        val content = FcmPayload.parse(
            mapOf("type" to "clear", "conv_id" to "u_1002_u_2005", "conv_seq" to "42", "badge" to "0", "title" to "", "body" to ""),
        )
        assertEquals(true, content?.clear)
        assertEquals(false, content?.retract)
        assertEquals(42L, content?.convSeq)
    }

    @Test
    fun `发送人头像——群聊用群头像，私聊用对方头像，空串当没有`() {
        val group = FcmPayload.parse(
            mapOf("conv_id" to "g_1", "sender_avatar" to "/avatars/a.jpg", "group_avatar" to "/avatars/g.jpg"),
        )
        assertEquals("/avatars/g.jpg", group?.iconAvatar)
        val private = FcmPayload.parse(mapOf("conv_id" to "u_1_u_2", "sender_avatar" to "/avatars/a.jpg", "group_avatar" to ""))
        assertEquals("/avatars/a.jpg", private?.iconAvatar)
        assertEquals(null, FcmPayload.parse(mapOf("conv_id" to "u_1_u_2"))?.iconAvatar)
    }

    @Test
    fun `头像地址只补自家服务器的 avatars 目录`() {
        assertEquals("http://10.0.2.2:8080/avatars/a.jpg", FcmPayload.avatarUrl("/avatars/a.jpg", "10.0.2.2:8080", useTls = false))
        assertEquals("https://im.example.com/avatars/a.jpg", FcmPayload.avatarUrl("/avatars/a.jpg", "im.example.com", useTls = true))
        for (bad in listOf("https://evil.example/a.jpg", "/uploads/a.jpg", "/avatars/../uploads/a.jpg", "", null)) {
            assertEquals(null, FcmPayload.avatarUrl(bad, "10.0.2.2:8080", useTls = false))
        }
        assertEquals(null, FcmPayload.avatarUrl("/avatars/a.jpg", "evil.example@10.0.2.2", useTls = false))
        assertEquals(null, FcmPayload.avatarUrl("/avatars/a.jpg", "", useTls = false))
    }

    @Test
    fun `群聊一行——发送人单列，正文不带「名字」前缀`() {
        val line = FcmPayload.parse(
            mapOf("conv_id" to "g_1", "conv_seq" to "7", "title" to "老同学群", "body" to "小明: 开会了",
                "sender_name" to "小明", "bare_body" to "开会了"),
        )?.toLine(nowMs = 5)
        assertEquals(ConversationLine(7, "小明", "开会了", 5), line)
    }

    @Test
    fun `群聊老服务端没给 bare_body——退回整句、发送人留空`() {
        val line = FcmPayload.parse(mapOf("conv_id" to "g_1", "conv_seq" to "7", "body" to "小明: 开会了"))?.toLine(5)
        assertEquals(ConversationLine(7, "", "小明: 开会了", 5), line)
    }

    @Test
    fun `单聊一行——发送人就是标题`() {
        val line = FcmPayload.parse(
            mapOf("conv_id" to "u_1_u_2", "conv_seq" to "3", "title" to "老王", "body" to "在吗", "bare_body" to "x"),
        )?.toLine(5)
        assertEquals(ConversationLine(3, "老王", "在吗", 5), line)
    }

    @Test
    fun `没有 seq 认不出是哪条——不成行`() {
        assertEquals(null, FcmPayload.parse(mapOf("conv_id" to "u_1_u_2", "body" to "hi"))?.toLine(5))
    }

    @Test
    fun `头像取失败后一分钟内只用本地缓存，之后再联网`() {
        assertEquals(true, FcmPayload.avatarNetworkAllowed(lastFailureMs = 0, nowMs = 5))
        assertEquals(false, FcmPayload.avatarNetworkAllowed(lastFailureMs = 1_000, nowMs = 1_000 + 59_999))
        assertEquals(true, FcmPayload.avatarNetworkAllowed(lastFailureMs = 1_000, nowMs = 1_000 + 60_000))
    }
}
