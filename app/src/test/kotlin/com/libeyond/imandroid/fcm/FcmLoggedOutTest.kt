package com.libeyond.imandroid.fcm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本机没登录时，会弹出新东西的推送一律丢，收起已有通知的照常处理（[FcmPayload.dropWhenLoggedOut]）。
 * 2026-10-08 Pixel：本机已在登录页，user1002 的来电通知照样弹出来。
 */
class FcmLoggedOutTest {

    private fun parse(vararg kv: Pair<String, String>) =
        FcmPayload.parse(mapOf("conv_id" to "u_1_u_2", "conv_seq" to "5") + kv)!!

    @Test
    fun `新消息、来电、未接都丢`() {
        assertEquals(true, FcmPayload.dropWhenLoggedOut(parse("title" to "a", "body" to "b")))
        assertEquals(true, FcmPayload.dropWhenLoggedOut(parse("type" to "call", "call_id" to "c", "call_kind" to "incoming")))
        assertEquals(true, FcmPayload.dropWhenLoggedOut(parse("type" to "call", "call_id" to "c", "call_kind" to "missed")))
    }

    @Test
    fun `撤回、别处已读、来电结束照常处理（只收起已有通知）`() {
        assertEquals(false, FcmPayload.dropWhenLoggedOut(parse("type" to "retract")))
        assertEquals(false, FcmPayload.dropWhenLoggedOut(parse("type" to "clear")))
        assertEquals(false, FcmPayload.dropWhenLoggedOut(parse("type" to "call", "call_id" to "c", "call_kind" to "ended")))
    }
}
