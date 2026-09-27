package com.libeyond.imandroid.data

import java.io.File
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话记录渲染：读**三端共用的向量** `call_record.json`（真源在 IMServer `docs/conformance/`，
 * 这里的 `src/test/resources/call_record.json` 是拷贝；真源在旁边时会比对，防漂移）。
 * 改文案 = 先改向量。
 */
class CallRecordTest {

    private val text: String =
        checkNotNull(javaClass.classLoader?.getResource("call_record.json")) { "缺 call_record.json" }.readText()

    @Test fun conformanceVectors() {
        val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        assertTrue("向量太少", cases.size >= 40)
        for (el in cases) {
            val c = el.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val content = c["content"]!!.jsonObject.toString()
            val sender = c["viewerIsSender"]!!.jsonPrimitive.boolean
            val expect = c["expect"]!!.jsonObject
            val got = CallRecord.renderRaw(content, sender, c["senderName"]?.jsonPrimitive?.content.orEmpty())
            assertEquals("$name text", expect["text"]!!.jsonPrimitive.content, got.text)
            assertEquals(
                "$name tone",
                expect["tone"]!!.jsonPrimitive.content == "missed",
                got.tone == CallRecord.Tone.Missed,
            )
            assertEquals("$name tappable", expect["tappable"]!!.jsonPrimitive.boolean, got.tappable)
            assertEquals("$name preview", expect["preview"]!!.jsonPrimitive.content, got.preview)
        }
    }

    @Test fun resourceMatchesSourceOfTruthWhenPresent() {
        // 测试的工作目录是 app/，真源在 ../../IMServer；不在旁边（CI）就跳过
        val src = File("../../IMServer/docs/conformance/call_record.json")
        if (src.exists()) assertEquals("向量拷贝与真源不一致，先同步", src.readText(), text)
    }

    @Test fun badContentDegradesWithoutJson() {
        for (bad in listOf("not json", "{}", """{"cid":"x","m":"text"}""", """{"m":"audio"}""")) {
            assertNull(bad, CallRecord.parse(bad))
            val r = CallRecord.renderRaw(bad, true)
            assertFalse(r.text.contains("{"))
            assertFalse(r.tappable)
        }
    }

    @Test fun encodeRoundTripsAndOnlyCarriesFourKeys() {
        val one = CallRecord.encode("call-77a1", video = true, reason = "hangup", durationSec = 201, isGroup = false)
        assertEquals("""{"cid":"call-77a1","m":"video","r":"hangup","d":201}""", one)
        val g = CallRecord.encode("call-1", video = false, reason = "no_answer", durationSec = 0, isGroup = true)
        assertEquals("""{"cid":"call-1","m":"audio","r":"no_answer","d":0,"g":1}""", g)
        val back = CallRecord.parse(g)!!
        assertTrue(back.isGroup)
        assertEquals("no_answer", back.reason)
    }

    @Test fun onlyCalleeMissedPreviewIsRed() {
        assertTrue(CallRecord.isMissedPreview(CallRecord.preview("""{"cid":"a","m":"video","r":"no_answer","d":0}""", false)))
        assertFalse(CallRecord.isMissedPreview(CallRecord.preview("""{"cid":"a","m":"video","r":"no_answer","d":0}""", true)))
        assertFalse(CallRecord.isMissedPreview(CallRecord.preview("""{"cid":"a","m":"audio","r":"reject","d":0}""", false)))
        // cancel 被叫侧文案是「对方已取消」而不是「未接来电」，但仍要标红（2026-09-27 细化时最容易漏改 isMissedPreview 的后缀判据）。
        assertTrue(CallRecord.isMissedPreview(CallRecord.preview("""{"cid":"a","m":"audio","r":"cancel","d":0}""", false)))
        assertFalse(CallRecord.isMissedPreview(CallRecord.preview("""{"cid":"a","m":"audio","r":"cancel","d":0}""", true)))
    }
}
