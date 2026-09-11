package com.libeyond.imandroid.sdk.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改密轮换出的续期凭据什么时候才能落盘（`TokenSession.shouldAdoptRotated`）。
 *
 * 改密请求挂在 `client.scope` 上、不随页面取消，应答可能晚于「退出登录」回来——
 * 那时照写，就在刚清空的本机里复活一枚仍然有效的长效凭据（2026-09-11 复查抓出）。
 */
class RotatedRefreshGuardTest {

    private fun adopt(fresh: String?, started: String?, current: String?) =
        TokenSession.shouldAdoptRotated(fresh, started, current)

    @Test
    fun `同一账号仍在登录则落盘`() {
        assertTrue(adopt("rt-new", "1000000001", "1000000001"))
    }

    @Test
    fun `应答回来前已退出登录则不写`() {
        assertFalse(adopt("rt-new", "1000000001", null))
        assertFalse(adopt("rt-new", "1000000001", ""))
    }

    @Test
    fun `期间换了号则不写，免得把 A 的凭据写给 B`() {
        assertFalse(adopt("rt-new", "1000000001", "1000000002"))
    }

    @Test
    fun `服务端没下发新凭据则不动`() {
        assertFalse(adopt(null, "1000000001", "1000000001"))
        assertFalse(adopt("", "1000000001", "1000000001"))
    }

    @Test
    fun `发起时就没登录则不写`() {
        assertFalse(adopt("rt-new", null, null))
    }
}
