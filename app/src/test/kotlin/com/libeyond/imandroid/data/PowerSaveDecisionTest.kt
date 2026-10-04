package com.libeyond.imandroid.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `powerSaveActive`：读三端共用向量 `power_save.json`（真源 IMServer `docs/conformance/`，
 * `src/test/resources/` 是拷贝，真源在旁边时比对防漂移——同 [AlertDecisionTest]）。
 */
class PowerSaveDecisionTest {

    private val text: String =
        checkNotNull(javaClass.classLoader?.getResource("power_save.json")) { "缺 power_save.json" }.readText()

    @Test
    fun conformanceVectors() {
        val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        assertTrue("向量太少", cases.size >= 13)
        for (el in cases) {
            val c = el.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(name, c["active"]!!.jsonPrimitive.boolean, powerSaveActive(ctxFrom(c["ctx"]!!.jsonObject)))
        }
    }

    @Test
    fun resourceMatchesSourceOfTruthWhenPresent() {
        val src = File("../../IMServer/docs/conformance/power_save.json")
        if (src.exists()) assertEquals("向量拷贝与真源不一致，先同步", src.readText(), text)
    }

    @Test
    fun reasonPriorityAlwaysThenBatteryThenSystem() {
        val base = PowerSaveContext(PowerSaveMode.AUTO, 15, 5, false, true, true)
        assertEquals(PowerSaveReason.BATTERY, PowerSaveDecision.reason(base))
        assertEquals(PowerSaveReason.ALWAYS, PowerSaveDecision.reason(base.copy(mode = PowerSaveMode.ALWAYS)))
        assertEquals(PowerSaveReason.SYSTEM, PowerSaveDecision.reason(base.copy(charging = true)))
        assertNull(PowerSaveDecision.reason(base.copy(charging = true, followSystem = false)))
    }

    @Test
    fun thresholdClampAndPrefsRoundTrip() {
        assertEquals(5, PowerSaveDecision.clampThreshold(0))
        assertEquals(50, PowerSaveDecision.clampThreshold(99))
        assertEquals(20, PowerSaveDecision.clampThreshold(20))
        val p = PowerSavingPrefs(PowerSaveMode.AUTO, 30, false, false, false, false, true)
        assertEquals(p, PowerSavingPrefs.decode(p.encode()::get))
        // 坏值：各键独立回落默认，阈值夹紧
        val bad = mapOf("mode" to "weird", "threshold" to "999", "followSystem" to "maybe")
        val d = PowerSavingPrefs.decode(bad::get)
        assertEquals(PowerSaveMode.OFF, d.mode)
        assertEquals(50, d.threshold)
        assertTrue(d.followSystem)
    }

    @Test
    fun effectiveIsUserValueAndNotActive() {
        val prefs = PowerSavingPrefs(mode = PowerSaveMode.ALWAYS, autoDownload = true, videoPreload = false)
        val on = PowerSaveStatus.of(prefs, true, BatteryReading(80, true, null))
        assertTrue(on.active)
        assertEquals(false, on.animations); assertEquals(false, on.autoDownload); assertEquals(3, on.pausedCount) // 动画/下载/后台连接为开，视频预加载用户已关 → 不算
        // 推送不可达：后台连接本来就不生效，不算被暂停
        assertEquals(2, PowerSaveStatus.of(prefs, true, BatteryReading(80, true, null), backgroundAvailable = false).pausedCount)
        val off = PowerSaveStatus.of(prefs.copy(mode = PowerSaveMode.OFF), true, BatteryReading(80, true, null))
        assertEquals(true, off.autoDownload)     // 退出后自然回到用户原值
        assertEquals(false, off.videoPreload)    // 用户自己关的仍是关
        assertEquals(0, off.pausedCount)
    }

    private fun ctxFrom(o: JsonObject): PowerSaveContext {
        fun nInt(k: String) = o[k]!!.takeIf { it != JsonNull }?.jsonPrimitive?.int
        fun nBool(k: String) = o[k]!!.takeIf { it != JsonNull }?.jsonPrimitive?.boolean
        return PowerSaveContext(
            mode = PowerSaveMode.fromWire(o["mode"]!!.jsonPrimitive.content),
            threshold = o["threshold"]!!.jsonPrimitive.int,
            level = nInt("level"),
            charging = nBool("charging"),
            followSystem = o["followSystem"]!!.jsonPrimitive.boolean,
            systemSaver = nBool("systemSaver"),
        )
    }
}
