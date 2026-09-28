package com.libeyond.imandroid.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * im-rtc 接入的两条判据：配置缺项就不可用（并说清缺哪项）、宿主 id 进 im-rtc 前的字段校验。
 * 字段从四项（wsUrl/appId/keyId/secret）精简为只剩 wsUrl 时（换票改由 IMServer 代理）同步更新。
 */
class RtcConfigTest {

    private val full = RtcConfig(wsUrl = "ws://h/v1/ws")

    @Test
    fun full_config_is_usable() {
        assertTrue(full.isUsable)
        assertTrue(full.missing.isEmpty())
    }

    @Test
    fun missing_ws_url_is_named() {
        assertEquals(listOf("rtc.wsUrl"), full.copy(wsUrl = "").missing)
        assertFalse(full.copy(wsUrl = "").isUsable)
        assertFalse(full.copy(wsUrl = "  ").isUsable) // 全空白同样算缺
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
