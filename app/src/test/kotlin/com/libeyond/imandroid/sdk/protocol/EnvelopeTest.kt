package com.libeyond.imandroid.sdk.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信封的线格式契约（PROTOCOL.md §2）。
 *
 * 这些不是「测 kotlinx.serialization 会不会用」，测的是**三端共享的协议约定**：
 * 未知 type/字段不能崩、默认值不上线、data 延迟解析。
 */
class EnvelopeTest {

    @Test
    fun `解析基本信封`() {
        val env = ProtocolJson.decodeFromString<Envelope>(
            """{"type":"ack","seq":1024,"data":{"conv_seq":7}}"""
        )
        assertEquals(FrameType.ACK, env.type)
        assertEquals(1024L, env.seq)
        val data = env.data as JsonObject
        assertEquals("7", data["conv_seq"]!!.jsonPrimitive.content)
    }

    /**
     * PROTOCOL §2 红线：**客户端收到未知 type 必须忽略、不崩**。
     * 服务端只追加新类型，老客户端不得因此解析失败。
     */
    @Test
    fun `未知 type 不抛异常`() {
        val env = ProtocolJson.decodeFromString<Envelope>(
            """{"type":"some_future_frame","seq":1,"data":{"whatever":true}}"""
        )
        assertEquals("some_future_frame", env.type)
    }

    /** 同一条红线的另一半：负载里多出来的字段也不能让解析失败。 */
    @Test
    fun `未知字段不抛异常`() {
        val env = ProtocolJson.decodeFromString<Envelope>(
            """{"type":"pong","seq":2,"data":null,"server_added_field":"x"}"""
        )
        assertEquals(FrameType.PONG, env.type)
        assertNull(env.data)
    }

    /** 对齐 Go 侧 `omitempty`：默认值不该出现在线格式里。 */
    @Test
    fun `编码省略默认值`() {
        val json = ProtocolJson.encodeToString(Envelope(type = FrameType.PING))
        assertEquals("""{"type":"ping"}""", json)
    }

    @Test
    fun `编码保留非默认 seq`() {
        val json = ProtocolJson.encodeToString(Envelope(type = FrameType.SYNC_REQ, seq = 9))
        assertTrue(json.contains("\"seq\":9"))
    }

    /**
     * 帧类型常量必须与后端 `internal/protocol/envelope.go` 一致。
     * 这里钉住线格式字符串——改名会当场变红，而不是等到联调时收不到帧。
     */
    @Test
    fun `帧类型线格式字符串`() {
        assertEquals("send_msg", FrameType.SEND_MSG)
        assertEquals("new_msg", FrameType.NEW_MSG)
        assertEquals("window_req", FrameType.WINDOW_REQ)
        assertEquals("window_resp", FrameType.WINDOW_RESP)
        assertEquals("conv_bump", FrameType.CONV_BUMP)
        assertEquals("capabilities_update", FrameType.CAPABILITIES_UPDATE)
        assertEquals("voice_transcript", FrameType.VOICE_TRANSCRIPT)
        assertEquals("msg_hidden", FrameType.MSG_HIDDEN)
    }

    @Test
    fun `caption 只允许媒体与文件`() {
        assertTrue(contentTypeAllowsCaption("image"))
        assertTrue(contentTypeAllowsCaption("video"))
        assertTrue(contentTypeAllowsCaption("file"))
        // voice 是录制的语音条，不带图说（PROTOCOL §4.1）
        assertTrue(!contentTypeAllowsCaption("voice"))
        assertTrue(!contentTypeAllowsCaption("text"))
    }
}
