package com.libeyond.imandroid.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `alertDecision` 判定：读**三端共用的向量** `alert_decision.json`（真源在 IMServer
 * `docs/conformance/`，这里的 `src/test/resources/alert_decision.json` 是拷贝；真源在旁边时会比对，
 * 防漂移——同 `CallRecordTest` 的写法）。改规则先改向量。
 *
 * 32 条向量里 mobile / desktop / browser 三种 platform 都有，**全部跑**——本端虽然只在移动端
 * 真正调用 [AlertDecision]，但它是三端共用的一份判定逻辑，desktop/browser 分支也要跟着测通，
 * 否则这份文件就不再是共用逻辑的忠实拷贝（见任务说明「桌面/浏览器向量也必须跑绿」）。
 */
class AlertDecisionTest {

    private val text: String =
        checkNotNull(javaClass.classLoader?.getResource("alert_decision.json")) { "缺 alert_decision.json" }.readText()

    @Test
    fun conformanceVectors() {
        val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        assertTrue("向量太少", cases.size >= 30)
        for (el in cases) {
            val c = el.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val ctx = ctxFrom(c["ctx"]!!.jsonObject)
            val expect = c["expect"]!!.jsonObject
            val got = AlertDecision.decide(ctx)
            assertEquals("$name sound", expect["sound"]!!.jsonPrimitive.boolean, got.sound)
            assertEquals("$name vibrate", expect["vibrate"]!!.jsonPrimitive.boolean, got.vibrate)
            assertEquals("$name banner", expect["banner"]!!.jsonPrimitive.boolean, got.banner)
            assertEquals("$name osNotify", expect["osNotify"]!!.jsonPrimitive.boolean, got.osNotify)
            val expectSoundId = expect["soundId"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            assertEquals("$name soundId", expectSoundId, got.soundId)
        }
    }

    @Test
    fun resourceMatchesSourceOfTruthWhenPresent() {
        // 测试的工作目录是 app/，真源在 ../../IMServer；不在旁边（CI）就跳过
        val src = File("../../IMServer/docs/conformance/alert_decision.json")
        if (src.exists()) assertEquals("向量拷贝与真源不一致，先同步", src.readText(), text)
    }

    private fun ctxFrom(o: JsonObject): AlertContext {
        fun b(key: String) = o[key]!!.jsonPrimitive.boolean
        fun l(key: String) = o[key]!!.jsonPrimitive.long
        fun s(key: String) = o[key]!!.jsonPrimitive.content
        val settings = o["settings"]!!.jsonObject
        return AlertContext(
            platform = s("platform"),
            isLive = b("isLive"),
            isSelf = b("isSelf"),
            isSystem = b("isSystem"),
            isRecalled = b("isRecalled"),
            isCallRecord = b("isCallRecord"),
            missedCallForMe = b("missedCallForMe"),
            convType = s("convType"),
            muted = b("muted"),
            mentionsMe = b("mentionsMe"),
            appActive = b("appActive"),
            windowFocused = b("windowFocused"),
            viewingConv = b("viewingConv"),
            inCall = b("inCall"),
            nowMs = l("nowMs"),
            lastSoundAtMs = l("lastSoundAtMs"),
            settings = settingsFrom(settings),
        )
    }

    private fun settingsFrom(o: JsonObject): NotificationSettings {
        fun type(key: String): NotifTypeSettings {
            val t = o[key]!!.jsonObject
            return NotifTypeSettings(
                enabled = t["enabled"]!!.jsonPrimitive.boolean,
                preview = t["preview"]!!.jsonPrimitive.boolean,
                sound = NotifSound.fromWire(t["sound"]!!.jsonPrimitive.content),
            )
        }
        val inApp = o["inApp"]!!.jsonObject
        val badge = o["badge"]!!.jsonObject
        val desktop = o["desktop"]!!.jsonObject
        return NotificationSettings(
            private = type("private"),
            group = type("group"),
            inApp = InAppSettings(
                sound = inApp["sound"]!!.jsonPrimitive.boolean,
                vibrate = inApp["vibrate"]!!.jsonPrimitive.boolean,
                preview = inApp["preview"]!!.jsonPrimitive.boolean,
            ),
            badge = BadgeSettings(includeMuted = badge["includeMuted"]!!.jsonPrimitive.boolean),
            desktop = DesktopSettings(
                enabled = desktop["enabled"]!!.jsonPrimitive.boolean,
                sound = desktop["sound"]!!.jsonPrimitive.boolean,
                volume = desktop["volume"]!!.jsonPrimitive.content.toInt(),
            ),
        )
    }
}
