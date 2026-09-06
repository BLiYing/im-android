package com.libeyond.imandroid.sdk.http

import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiExceptionTest {

    @Test
    fun `业务码原样保留`() {
        val e = ApiException(ErrCode.GROUP_BANNED, "你已被移出该群")
        assertEquals(300207, e.code)
    }

    @Test
    fun `token 失效两码都认`() {
        assertTrue(ApiException(ErrCode.TOKEN_INVALID, "").isAuthExpired)
        assertTrue(ApiException(ErrCode.TOKEN_EXPIRED, "").isAuthExpired)
        assertFalse(ApiException(ErrCode.NO_PERMISSION, "").isAuthExpired)
        assertFalse(ApiException(ErrCode.GROUP_BANNED, "").isAuthExpired)
    }

    /**
     * 传输层哨兵码不能与任何真实业务码相撞——撞了就分不清
     * 「服务端明确拒绝」与「根本没连上」，而这两者的处理完全相反
     * （前者清会话，后者必须保留会话）。
     */
    @Test
    fun `传输层哨兵码不与业务码相撞`() {
        val real = listOf(
            ErrCode.SUCCESS, ErrCode.PARAM_INVALID, ErrCode.INTERNAL,
            ErrCode.TOKEN_INVALID, ErrCode.FILE_TOO_LARGE, ErrCode.GROUP_BANNED,
        )
        assertFalse(ApiException.TRANSPORT in real)
        assertTrue(ApiException(ApiException.TRANSPORT, "").isTransport)
        assertFalse(ApiException(ErrCode.INTERNAL, "").isTransport)
    }

    @Test
    fun `Request ID 有前缀且够短`() {
        val id = HttpClient.newRequestId()
        assertTrue(id.startsWith("and-"))
        assertEquals(16, id.length)
    }
}
