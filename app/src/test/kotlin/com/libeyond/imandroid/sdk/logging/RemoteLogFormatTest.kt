package com.libeyond.imandroid.sdk.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开发期日志回传的格式与缓冲：汇到 IMServer 同一个文件里，字段对不上就没法 grep 分离来源。 */
class RemoteLogFormatTest {

    private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

    @Test
    fun line_carries_the_grep_labels_and_is_one_line() {
        val line = RemoteLogFormat.line(
            1700000000000, IMLog.Level.WARN, "a1b2c3d4", "IM.WS", "ws_closed",
            mapOf("code" to 1006, "uid" to "1003"), null,
        )
        assertFalse("NDJSON 一条一行", line.contains('\n'))
        val o = parse(line)
        assertEquals("1700000000000", o["ts"]!!.jsonPrimitive.content)
        assertEquals("warn", o["level"]!!.jsonPrimitive.content)
        assertEquals("a1b2c3d4", o["dev"]!!.jsonPrimitive.content)
        assertEquals("1003", o["uid"]!!.jsonPrimitive.content)
        assertEquals("android", o["plat"]!!.jsonPrimitive.content)
        assertEquals("[IM.WS] ws_closed code=1006", o["msg"]!!.jsonPrimitive.content)
    }

    @Test
    fun missing_uid_becomes_dash_and_newlines_stay_escaped() {
        val o = parse(
            RemoteLogFormat.line(1, IMLog.Level.INFO, "d", "T", "e", mapOf("text" to "a\nb"), null),
        )
        assertEquals("-", o["uid"]!!.jsonPrimitive.content)
        assertTrue(o["msg"]!!.jsonPrimitive.content.contains("a\nb"))
    }

    @Test
    fun buffer_drops_oldest_beyond_cap_and_hands_out_in_order_by_batch() {
        val b = LogBuffer(cap = 5, batch = 2)
        (1..7).forEach { b.add("l$it") }
        assertEquals(5, b.size)
        assertEquals(listOf("l3", "l4"), b.takeBatch())
        assertEquals(listOf("l5", "l6"), b.takeBatch())
        assertEquals(listOf("l7"), b.takeBatch())
        assertEquals(emptyList<String>(), b.takeBatch())
    }
}
