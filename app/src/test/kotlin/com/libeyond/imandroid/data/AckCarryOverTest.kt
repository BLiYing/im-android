package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ack 不回带的字段必须从待发行补——这个坑踩了三次（forwardFrom / groupId / 媒体元数据），
 * 每次都只在**发送者自己那一侧**坏，对端正常，所以自测很难发现。
 */
class AckCarryOverTest {

    private fun pending() = PendingMessageEntity(
        ownerUid = "me", clientMsgId = "c1", convId = "conv", to = "u2",
        contentType = ContentType.VIDEO, content = "content://local/9",
        caption = "看这个", fileName = "a.mp4", fileSize = 123,
        replyToConvSeq = 7, forwardFrom = "张三", groupId = "alb-x",
        mediaW = 1080, mediaH = 586, duration = 27351, poster = "/uploads/p.jpg",
    )

    private fun ackRow() = MessageEntity(
        ownerUid = "me", convId = "conv", convSeq = 42,
        serverMsgId = "s1", clientMsgId = "c1", sender = "me",
        contentType = ContentType.TEXT, timestamp = 1000,
    )

    @Test
    fun `待发行里的字段全部补进来`() {
        val row = AckCarryOver.enrich(ackRow(), pending())
        assertEquals(ContentType.VIDEO, row.contentType)
        assertEquals("content://local/9", row.content)
        assertEquals("看这个", row.caption)
        assertEquals("a.mp4", row.fileName)
        assertEquals(123L, row.fileSize)
        assertEquals(7L, row.replyToConvSeq)
        assertEquals("张三", row.forwardFrom)
        assertEquals("alb-x", row.groupId)
        assertEquals(1080, row.mediaW)
        assertEquals(586, row.mediaH)
        assertEquals(27351, row.duration)
        assertEquals("/uploads/p.jpg", row.poster)
    }

    @Test
    fun `ack 自己的字段不被覆盖`() {
        val row = AckCarryOver.enrich(ackRow(), pending())
        assertEquals(42L, row.convSeq)
        assertEquals("s1", row.serverMsgId)
        assertEquals(1000L, row.timestamp)
        assertEquals("me", row.sender)
    }

    @Test
    fun `没有待发行时原样返回`() {
        // 罕见但真实：进程重启后 ack 先到、待发行已被清掉
        assertEquals(ackRow(), AckCarryOver.enrich(ackRow(), null))
    }

    /**
     * **这条才是防第四次的那道闸**：两个实体的同名字段里，凡是不属于 ack 自身的，
     * 都必须在 [AckCarryOver.CARRIED] 里做过决定。新加一个随消息走的字段却忘了补，这里会红。
     */
    @Test
    fun `新增的同名字段必须显式做过决定`() {
        // ack 帧本身带的字段（`AckData`），不该从待发行取
        val fromAck = setOf(
            "ownerUid", "convId", "convSeq", "serverMsgId", "clientMsgId", "sender", "timestamp",
            // 待发行里有但语义不同 / 不入消息表的
            "to", "state", "createdAt", "errorCode",
        )
        // Compose 编译器会往类里塞一个合成的 `$stable` 字段，得滤掉
        fun fieldsOf(c: Class<*>) = c.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("$") }
            .map { it.name }.toSet()
        val pendingFields = fieldsOf(PendingMessageEntity::class.java)
        val messageFields = fieldsOf(MessageEntity::class.java)
        val shared = (pendingFields intersect messageFields) - fromAck
        assertEquals(
            "两个实体新增了同名字段却没在 AckCarryOver.CARRIED 里做决定（或反之）",
            shared.sorted(),
            AckCarryOver.CARRIED.sorted(),
        )
    }
}
