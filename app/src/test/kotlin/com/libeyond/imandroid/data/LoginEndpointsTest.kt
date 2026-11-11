package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.LoginEndpoints.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 登录页「长按 Logo ▸ 配置地址」的校验：两项都合法才保存，留空恢复默认（对齐 iOS saveEndpointHost:rtc:）。 */
class LoginEndpointsTest {

    @Test
    fun both_valid_are_trimmed() {
        assertEquals(
            Check.Ok("192.168.1.12:8080", "wss://rtc.example.com/v1/ws"),
            LoginEndpoints.check(" 192.168.1.12:8080 ", " wss://rtc.example.com/v1/ws ", useTls = false),
        )
    }

    @Test
    fun blank_means_restore_default() {
        assertEquals(Check.Ok(null, null), LoginEndpoints.check("", "  ", useTls = false))
    }

    @Test
    fun host_checked_before_rtc() {
        assertEquals(Check.HostInvalid, LoginEndpoints.check("a b", "nope", useTls = false))
        assertEquals(Check.RtcInvalid, LoginEndpoints.check("im.example.com", "http://h/v1/ws", useTls = false))
    }

    @Test
    fun host_accepts_matching_scheme_and_trailing_slash() {
        assertEquals("im.example.com", LoginEndpoints.normalizedHost("https://im.example.com/", useTls = true))
        assertEquals("10.0.2.2:8080", LoginEndpoints.normalizedHost("http://10.0.2.2:8080", useTls = false))
        assertEquals("[::1]:8080", LoginEndpoints.normalizedHost("[::1]:8080", useTls = false))
    }

    @Test
    fun host_rejects_bad_input() {
        // 协议与构建不一致：不假装切换了协议
        assertNull(LoginEndpoints.normalizedHost("https://im.example.com", useTls = false))
        assertNull(LoginEndpoints.normalizedHost("ws://im.example.com", useTls = false))
        assertNull(LoginEndpoints.normalizedHost("10.0.2.2:", useTls = false))
        assertNull(LoginEndpoints.normalizedHost("10.0.2.2:abc", useTls = false))
        assertNull(LoginEndpoints.normalizedHost("im.example.com/base", useTls = false))
        assertNull(LoginEndpoints.normalizedHost("user@im.example.com", useTls = false))
        assertNull(LoginEndpoints.normalizedHost("http://", useTls = false))
    }
}
