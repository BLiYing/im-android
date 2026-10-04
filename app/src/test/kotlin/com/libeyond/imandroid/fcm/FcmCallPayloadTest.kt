package com.libeyond.imandroid.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通话提醒（服务端 `type=call`，PUSH_M5_DESIGN §3.8）的载荷解析。 */
class FcmCallPayloadTest {

    private fun call(vararg extra: Pair<String, String>) = FcmPayload.parse(
        mapOf("conv_id" to "g_1", "title" to "工作群", "body" to "老板: 邀请你加入群视频通话", "conv_seq" to "0", "type" to "call") + extra,
    )

    @Test
    fun `来电横幅解出 call_id 与形态`() {
        val content = call("call_id" to "call-1", "call_kind" to "incoming", "media" to "video")
        assertEquals(FcmCallNotice("call-1", FcmCallNotice.INCOMING, "video"), content?.call)
        assertEquals(true, content?.call?.isVideo)
    }

    @Test
    fun `缺 call_id 或 call_kind——不算通话提醒，也不会当成消息弹出来`() {
        val noId = call("call_kind" to "incoming")
        assertNull(noId?.call)
        assertNull(noId?.toLine(0L)) // conv_seq=0 认不出是哪条消息，不成行
        assertNull(call("call_id" to "call-1")?.call)
    }

    @Test
    fun `别的类型带了 call_id 也不算`() {
        val content = FcmPayload.parse(mapOf("conv_id" to "g_1", "type" to "retract", "call_id" to "c", "call_kind" to "ended"))
        assertNull(content?.call)
    }
}
