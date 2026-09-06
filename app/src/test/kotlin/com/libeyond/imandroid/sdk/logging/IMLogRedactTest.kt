package com.libeyond.imandroid.sdk.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏与截断（`../IMServer/docs/LOGGING.md` §5 / §7.5）。
 * §7.5 明确要求「补脱敏和超长/二进制边界测试」，故这两条从第一天就有。
 */
class IMLogRedactTest {

    @Test
    fun `敏感字段被替换`() {
        val out = IMLog.redact(
            mapOf(
                "token" to "eyJhbGciOi...",
                "password" to "123456",
                "authorization" to "Bearer xxx",
                "phone" to "13800138000",
                "conv_id" to "u_1001_u_1002",
            )
        )
        assertEquals("***", out["token"])
        assertEquals("***", out["password"])
        assertEquals("***", out["authorization"])
        assertEquals("***", out["phone"])
        // 非敏感字段原样保留——脱敏不能把有用的排障字段一起抹掉
        assertEquals("u_1001_u_1002", out["conv_id"])
    }

    @Test
    fun `敏感字段判定不区分大小写`() {
        val out = IMLog.redact(mapOf("Authorization" to "Bearer x", "TOKEN" to "y"))
        assertEquals("***", out["Authorization"])
        assertEquals("***", out["TOKEN"])
    }

    @Test
    fun `超长正文被截断并标注`() {
        val long = "a".repeat(20 * 1024)
        val out = IMLog.redact(mapOf("body" to long))
        val v = out["body"] as String
        assertTrue("应被截断", v.length < long.length)
        assertTrue("应标注截断量", v.contains("truncated"))
    }

    @Test
    fun `恰好等于上限不截断`() {
        val exact = "a".repeat(16 * 1024)
        val out = IMLog.redact(mapOf("body" to exact))
        assertEquals(exact, out["body"])
    }

    @Test
    fun `非字符串值原样保留`() {
        val out = IMLog.redact(mapOf("count" to 42, "ok" to true, "nothing" to null))
        assertEquals(42, out["count"])
        assertEquals(true, out["ok"])
        assertEquals(null, out["nothing"])
    }
}
