package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.data.DownloadSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PUT /api/v1/download-settings` 的线上形状。两条都是 2026-09-11 实测撞到的：
 *
 * 1. body **顶层就是** `{cellular, wifi}`（iOS `IMHTTPService`、Web `putDownloadSettings` 同），
 *    多包一层 `settings` 服务端找不到两个网络 → 400「请检查网络」，用户永远存不上；
 * 2. 服务端 Go 结构体没有指针探测字段级缺失，缺 `enabled/single/group` 解码成 **false**——
 *    所以等于 Kotlin 默认值（true）的字段**也必须上线**，否则一次保存把整套策略静默清成全关。
 */
class DownloadSettingsWireTest {

    private val d = DownloadPolicy.defaults()

    @Test
    fun `顶层直接是两个网络，不套 settings`() {
        val body = DownloadSettingsApi.putBody(d)
        assertEquals(setOf("cellular", "wifi"), body.keys)
    }

    @Test
    fun `等于默认值的字段也上线：enabled、single、group、图片规则一个不少`() {
        val body = DownloadSettingsApi.putBody(d)
        for (net in listOf("cellular", "wifi")) {
            val p = body.getValue(net).jsonObject
            assertEquals(setOf("enabled", "image", "video", "file"), p.keys)
            assertTrue(p.getValue("enabled").jsonPrimitive.boolean)
            for (cat in listOf("image", "video", "file")) {
                val r = p.getValue(cat).jsonObject
                assertEquals("$net.$cat", setOf("single", "group", "max_bytes"), r.keys)
                assertTrue(r.getValue("single").jsonPrimitive.boolean)
                assertTrue(r.getValue("group").jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun `关掉 Wi-Fi 总开关：只有那一位变，其余原样`() {
        val off = d.copy(wifi = d.wifi.copy(enabled = false))
        val body = DownloadSettingsApi.putBody(off)
        assertFalse(body.wifi().getValue("enabled").jsonPrimitive.boolean)
        assertTrue(body.cellular().getValue("enabled").jsonPrimitive.boolean)
        assertEquals(15L shl 20, body.wifi().getValue("video").jsonObject.getValue("max_bytes").jsonPrimitive.long)
        assertEquals(0L, body.wifi().getValue("image").jsonObject.getValue("max_bytes").jsonPrimitive.long)
    }

    @Test
    fun `连整份都是 Kotlin 默认值的网络也不省略`() {
        // DownloadSettings() 的两个网络正好等于 NetworkPolicy()：encodeDefaults=false 会把整块吞掉
        val body = DownloadSettingsApi.putBody(DownloadSettings())
        assertEquals(setOf("cellular", "wifi"), body.keys)
    }

    private fun JsonObject.wifi() = getValue("wifi").jsonObject
    private fun JsonObject.cellular() = getValue("cellular").jsonObject
}
