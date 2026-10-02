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

/**
 * Go 后端的 nil slice / nil map 会 marshal 成 `null` 而不是 `[]` / `{}`。
 * 这些用例钉住「收到 null 集合不能炸」——2026-09-07 实测撞到过：
 * 没有新消息的会话下发 `"messages": null`，整帧 sync_resp 解析失败，
 * 界面表现为「会话列表有，点进去一条消息都没有」。
 */
class GoNullCollectionTest {

    @Test
    fun `sync_resp 的 messages 为 null 时退化成空列表`() {
        val json = """
            {"conversations":[{"conv_id":"u_1_2","messages":null,
             "latest_conv_seq":0,"covered_conv_seq":57,"has_more":false}]}
        """.trimIndent()
        val d = ProtocolJson.decodeFromString(
            com.libeyond.imandroid.sdk.protocol.SyncRespData.serializer(), json
        )
        assertEquals(1, d.conversations.size)
        assertTrue(d.conversations[0].messages.isEmpty())
        assertEquals(57L, d.conversations[0].coveredConvSeq)
    }

    @Test
    fun `conversations 整个为 null 时退化成空列表`() {
        val d = ProtocolJson.decodeFromString(
            com.libeyond.imandroid.sdk.protocol.SyncRespData.serializer(), """{"conversations":null}"""
        )
        assertTrue(d.conversations.isEmpty())
    }

    @Test
    fun `非空字符串字段收到 null 时退化成默认值`() {
        val d = ProtocolJson.decodeFromString(
            com.libeyond.imandroid.sdk.protocol.MessageData.serializer(),
            """{"conv_id":"u_1_2","conv_seq":7,"content":null,"content_type":null,"from":"1001"}""",
        )
        assertEquals("", d.content)
        assertEquals("text", d.contentType)
        assertEquals(7L, d.convSeq)
    }
}

/**
 * `sync_req`/`sync_resp` 的积压深度闸门（`max_gap`/`too_long`/`head_conv_seq`，
 * OFFLINE_BACKLOG_DESIGN §4.4）。服务端 `internal/protocol/envelope.go` 的
 * `SyncCursor.MaxGap` 是 Go 的 `*int64`：不带=不限深度（老行为），带了才限。
 */
class SyncGapTest {

    @Test
    fun `maxGap 为 null 时编码省略字段（对齐 Go 指针不带=不限深度）`() {
        val json = ProtocolJson.encodeToString(
            com.libeyond.imandroid.sdk.protocol.SyncCursorItem.serializer(),
            com.libeyond.imandroid.sdk.protocol.SyncCursorItem("u_1_2", 57L),
        )
        assertEquals("""{"conv_id":"u_1_2","since_conv_seq":57}""", json)
    }

    @Test
    fun `maxGap 非空时编码带上 max_gap`() {
        val json = ProtocolJson.encodeToString(
            com.libeyond.imandroid.sdk.protocol.SyncCursorItem.serializer(),
            com.libeyond.imandroid.sdk.protocol.SyncCursorItem("u_1_2", 57L, maxGap = 400L),
        )
        assertTrue(json.contains("\"max_gap\":400"))
    }

    @Test
    fun `conv_bump 解析出最新位点与极简预览`() {
        val json = """{"items":[{"conv_id":"g_super","latest_seq":101200,"from":"u1","from_nickname":"小明","preview":"[图片]"}]}"""
        val d = ProtocolJson.decodeFromString(com.libeyond.imandroid.sdk.protocol.ConvBumpData.serializer(), json)
        val i = d.items.single()
        assertEquals("g_super", i.convId)
        assertEquals(101200L, i.latestSeq)
        assertEquals("[图片]", i.preview)
        assertEquals("小明", i.fromNickname)
    }

    @Test
    fun `conv_bump 缺字段不崩 老服务端兼容`() {
        val d = ProtocolJson.decodeFromString(
            com.libeyond.imandroid.sdk.protocol.ConvBumpData.serializer(), """{"items":[{"conv_id":"g","latest_seq":5}]}""",
        )
        assertEquals("", d.items.single().preview)
    }

    /** 超级群发 0：**0 不是「省略」**。若被当默认值丢掉，服务端会按老客户端不限深度、整段追平。 */
    @Test
    fun `maxGap 为 0 时仍编码 max_gap（超级群的永不自动补拉）`() {
        val json = ProtocolJson.encodeToString(
            com.libeyond.imandroid.sdk.protocol.SyncCursorItem.serializer(),
            com.libeyond.imandroid.sdk.protocol.SyncCursorItem("g_super", 57L, maxGap = 0L),
        )
        assertTrue(json, json.contains("\"max_gap\":0"))
    }

    @Test
    fun `too_long 响应解析出 head_conv_seq 且游标原样不动`() {
        val json = """
            {"conversations":[{"conv_id":"g_super","messages":[],
             "covered_conv_seq":1200,"has_more":false,"too_long":true,"head_conv_seq":101200}]}
        """.trimIndent()
        val d = ProtocolJson.decodeFromString(
            com.libeyond.imandroid.sdk.protocol.SyncRespData.serializer(), json,
        )
        val c = d.conversations.single()
        assertTrue(c.tooLong)
        assertEquals(101200L, c.headConvSeq)
        assertEquals(1200L, c.coveredConvSeq) // 原样等于请求的 since，没有推进
        assertTrue(c.messages.isEmpty())
    }

    @Test
    fun `不带 too_long 与 head_conv_seq 字段时退化成默认值（老服务端兼容）`() {
        val json = """{"conversations":[{"conv_id":"u_1_2","messages":[],"covered_conv_seq":57,"has_more":false}]}"""
        val d = ProtocolJson.decodeFromString(
            com.libeyond.imandroid.sdk.protocol.SyncRespData.serializer(), json,
        )
        val c = d.conversations.single()
        assertTrue(!c.tooLong)
        assertEquals(0L, c.headConvSeq)
    }

    /**
     * app_state（PROTOCOL §6.12，M5 批次 2）线格式：只有 `state` 一个字段，值必须是
     * 服务端认的 `"foreground"`/`"background"` 字面量——写错成别的拼法（如 `"fg"`）服务端会
     * 按「未知 state 值」静默忽略（`internal/gateway/client.go` `applyAppState`），不报错也不生效，
     * 这条测试钉住不会有人手滑改错字面量。
     */
    @Test
    fun `AppStateData 编码出的字面量与服务端约定一致`() {
        val fg = ProtocolJson.encodeToString(AppStateData.serializer(), AppStateData(state = "foreground"))
        val bg = ProtocolJson.encodeToString(AppStateData.serializer(), AppStateData(state = "background"))
        assertEquals("""{"state":"foreground"}""", fg)
        assertEquals("""{"state":"background"}""", bg)
    }
}
