package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportApiTest {
    private fun parse(s: String) = ProtocolJson.decodeFromString(ExportJob.serializer(), s)

    @Test fun `完成任务对象解析`() {
        val j = parse("""{"id":"e1","status":"done","include_media":true,"formats":["html","json"],
          "progress":{"done_convs":12,"total_convs":12},
          "result":{"size_bytes":134217728,"conv_count":12,"msg_count":8431},
          "created_at":1,"finished_at":2,"expires_at":3,"error_code":null,"future_field":"x"}""")
        assertEquals(ExportStatus.Done, j.state)
        assertTrue(j.includeMedia)
        assertEquals(listOf("html", "json"), j.formats)
        assertEquals(12, j.progress.totalConvs)
        assertEquals(8431L, j.result?.msgCount)
        assertEquals(3L, j.expiresAt)
    }

    @Test fun `进行中对象 result 为 null 字段缺省`() {
        val j = parse("""{"id":"e2","status":"running","progress":{"done_convs":3,"total_convs":12},"result":null}""")
        assertEquals(ExportStatus.Running, j.state)
        assertTrue(j.state.isActive)
        assertNull(j.result)
        assertEquals(0L, j.finishedAt)
    }

    @Test fun `全部状态映射且未知值不抛`() {
        listOf("queued" to ExportStatus.Queued, "running" to ExportStatus.Running, "done" to ExportStatus.Done,
            "failed" to ExportStatus.Failed, "canceled" to ExportStatus.Canceled, "expired" to ExportStatus.Expired,
            "" to ExportStatus.Unknown, "zzz" to ExportStatus.Unknown).forEach { (w, s) -> assertEquals(w, s, ExportStatus.parse(w)) }
        assertFalse(ExportStatus.Done.isActive)
        assertFalse(ExportStatus.Unknown.isActive)
    }

    @Test fun `发起请求体字段名与格式数组`() {
        val b = ExportApi.startBody("pw", true, listOf("html", "json"))
        assertEquals("pw", b["password"]!!.jsonPrimitive.content)
        assertEquals("true", b["include_media"]!!.jsonPrimitive.content)
        assertEquals(listOf("html", "json"), b["formats"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `retry_after_sec 解析`() {
        assertEquals(64800L, ExportApi.retryAfterSec(buildJsonObject { put("retry_after_sec", 64800) }))
        assertNull(ExportApi.retryAfterSec(null))
        assertNull(ExportApi.retryAfterSec(buildJsonObject { put("retry_after_sec", 0) }))
        assertNull(ExportApi.retryAfterSec(buildJsonObject { put("other", 1) }))
        assertNotNull(ExportApi.retryAfterSec(buildJsonObject { put("retry_after_sec", 1) }))
    }

    @Test fun `失败任务 error_code 是字符串枚举且可解析`() {
        listOf("interrupted", "internal").forEach { code ->
            val j = parse("""{"id":"e3","status":"failed","error_code":"$code","result":null}""")
            assertEquals(ExportStatus.Failed, j.state)
            assertEquals(code, j.errorCode)
        }
    }

    @Test fun `msg_count 超过 Int 上限不溢出`() {
        val j = parse("""{"id":"e4","status":"done","result":{"size_bytes":1,"conv_count":1,"msg_count":3000000000}}""")
        assertEquals(3_000_000_000L, j.result?.msgCount)
    }
}
