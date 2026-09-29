package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import java.io.File
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时免打扰判定 `isMutedNow` + 到期文案分类 `untilLabel`：读**三端共用的向量** `mute_state.json`
 * （真源在 IMServer `docs/conformance/`，这里的 `src/test/resources/mute_state.json` 是拷贝；
 * 真源在旁边时会比对，防漂移——同 `AlertDecisionTest` 的写法）。改规则先改向量。
 */
class MuteStateTest {

    private val text: String =
        checkNotNull(javaClass.classLoader?.getResource("mute_state.json")) { "缺 mute_state.json" }.readText()
    private val root = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `isMutedNow 向量`() {
        val cases = root["isMutedNow"]!!.jsonArray
        assertTrue("向量太少", cases.size >= 6)
        for (el in cases) {
            val c = el.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val got = MuteState.isMutedNow(
                muted = c["muted"]!!.jsonPrimitive.boolean,
                muteUntilMs = c["muteUntil"]!!.jsonPrimitive.long,
                nowMs = c["nowMs"]!!.jsonPrimitive.long,
            )
            assertEquals(name, c["expect"]!!.jsonPrimitive.boolean, got)
        }
    }

    @Test
    fun `untilLabel 向量`() {
        val cases = root["untilLabel"]!!.jsonArray
        assertTrue("向量太少", cases.size >= 8)
        for (el in cases) {
            val c = el.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val tzOffsetMinutes = c["tzOffsetMinutes"]!!.jsonPrimitive.int
            val zone = ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60)
            val got = MuteState.untilLabel(
                muteUntilMs = c["muteUntil"]!!.jsonPrimitive.long,
                nowMs = c["nowMs"]!!.jsonPrimitive.long,
                zone = zone,
            )
            val expect = c["expect"]!!.jsonObject
            val expectKind = when (expect["kind"]!!.jsonPrimitive.content) {
                "forever" -> MuteState.UntilLabel.Kind.FOREVER
                "today" -> MuteState.UntilLabel.Kind.TODAY
                "tomorrow" -> MuteState.UntilLabel.Kind.TOMORROW
                "date" -> MuteState.UntilLabel.Kind.DATE
                else -> error("未知 kind")
            }
            assertEquals("$name kind", expectKind, got.kind)
            assertEquals("$name time", expect["time"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content, got.time)
            assertEquals("$name month", expect["month"]?.takeIf { it != JsonNull }?.jsonPrimitive?.int, got.month)
            assertEquals("$name day", expect["day"]?.takeIf { it != JsonNull }?.jsonPrimitive?.int, got.day)
        }
    }

    @Test
    fun resourceMatchesSourceOfTruthWhenPresent() {
        // 测试的工作目录是 app/，真源在 ../../IMServer；不在旁边（CI）就跳过
        val src = File("../../IMServer/docs/conformance/mute_state.json")
        if (src.exists()) assertEquals("向量拷贝与真源不一致，先同步", src.readText(), text)
    }

    // ——— nearestFutureMuteUntil（§4.4 到期刷新用的定时器目标） ———

    private fun conv(id: String, muted: Boolean, muteUntil: Long) =
        ConversationEntity(ownerUid = "me", convId = id, muted = muted, muteUntil = muteUntil)

    @Test
    fun `没有定时免打扰会话时返回 null`() {
        assertNull(MuteState.nearestFutureMuteUntil(emptyList(), nowMs = 1000))
        // 永久免打扰（muteUntil=0）与未免打扰都不参与
        assertNull(
            MuteState.nearestFutureMuteUntil(
                listOf(conv("forever", muted = true, muteUntil = 0), conv("off", muted = false, muteUntil = 5000)),
                nowMs = 1000,
            ),
        )
    }

    @Test
    fun `取最近的一个未到期 mute_until，已过期的不算`() {
        val list = listOf(
            conv("expired", muted = true, muteUntil = 500),
            conv("near", muted = true, muteUntil = 2000),
            conv("far", muted = true, muteUntil = 3000),
        )
        assertEquals(2000L, MuteState.nearestFutureMuteUntil(list, nowMs = 1000))
    }
}
