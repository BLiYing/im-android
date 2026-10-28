package com.libeyond.imandroid.sdk.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DownloadCopyTest {
    private val data = ByteArray(200_000) { (it % 251).toByte() }

    @Test fun `长度一致时完整拷贝`() {
        val out = ByteArrayOutputStream()
        val n = HttpClient.copyVerified(ByteArrayInputStream(data), out, data.size.toLong()) {}
        assertEquals(data.size.toLong(), n)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun `未知长度不校验`() {
        val n = HttpClient.copyVerified(ByteArrayInputStream(data), ByteArrayOutputStream(), -1L) {}
        assertEquals(data.size.toLong(), n)
    }

    @Test fun `短于 Content-Length 抛 IOException`() {
        try {
            HttpClient.copyVerified(ByteArrayInputStream(data), ByteArrayOutputStream(), data.size + 10L) {}
            fail("应抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("不完整"))
        }
    }

    @Test fun `取消点抛出时中止且不再写入`() {
        val out = ByteArrayOutputStream()
        var chunks = 0
        try {
            HttpClient.copyVerified(ByteArrayInputStream(data), out, -1L) {
                if (++chunks == 2) throw kotlinx.coroutines.CancellationException("cancel")
            }
            fail("应抛 CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertTrue(out.size() < data.size)
    }

    @Test fun `逐块回报累计字节 最后等于总数`() {
        val seen = mutableListOf<Long>()
        HttpClient.copyVerified(ByteArrayInputStream(data), ByteArrayOutputStream(), -1L, { seen += it }) {}
        assertTrue(seen.size > 1)
        assertEquals(seen.sorted(), seen)
        assertEquals(data.size.toLong(), seen.last())
    }
}
