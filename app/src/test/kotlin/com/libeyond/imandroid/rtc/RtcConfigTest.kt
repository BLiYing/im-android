package com.libeyond.imandroid.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** im-rtc 接入的两条判据：配置缺项就不可用（并说清缺哪项）、宿主 id 进 im-rtc 前的字段校验。 */
class RtcConfigTest {

    private val full = RtcConfig(wsUrl = "ws://h/v1/ws", appId = "1", keyId = "dbg-1", secret = "s")

    @Test
    fun full_config_is_usable() {
        assertTrue(full.isUsable)
        assertTrue(full.missing.isEmpty())
    }

    @Test
    fun each_missing_field_is_named() {
        assertEquals(listOf("rtc.wsUrl"), full.copy(wsUrl = "").missing)
        assertEquals(listOf("rtc.appId"), full.copy(appId = " ").missing)
        assertEquals(listOf("rtc.keyId"), full.copy(keyId = "").missing)
        assertEquals(listOf("rtc.debugSecret"), full.copy(secret = "").missing)
        assertFalse(full.copy(secret = "").isUsable)
    }

    @Test
    fun ids_follow_protocol_rules() {
        assertNull(RtcIds.problem("uid", "10001"))
        assertNotNull(RtcIds.problem("uid", ""))
        assertNotNull(RtcIds.problem("uid", null))
        assertNotNull(RtcIds.problem("uid", "a b"))
        assertNotNull(RtcIds.problem("uid", "a\nb"))
        assertNull(RtcIds.problem("uid", "x".repeat(64)))
        assertNotNull(RtcIds.problem("uid", "x".repeat(65)))
        // 按字节数算，不是字符数：22 个汉字 = 66 字节
        assertNotNull(RtcIds.problem("uid", "群".repeat(22)))
    }
}
