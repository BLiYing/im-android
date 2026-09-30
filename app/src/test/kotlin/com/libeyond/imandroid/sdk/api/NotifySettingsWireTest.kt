package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.data.AccountNotifyFields
import com.libeyond.imandroid.data.BadgeSettings
import com.libeyond.imandroid.data.NotifSound
import com.libeyond.imandroid.data.NotifTypeSettings
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `{private,group,badge}` ↔ JSON 的编解码（PROTOCOL §6.13）。两条最容易错的线上形状：
 * `badge.include_muted` 是 snake_case，以及 `sound` 编成 wire 字符串（不是 Kotlin 枚举名）。
 */
class NotifySettingsWireTest {

    private val fields = AccountNotifyFields(
        private = NotifTypeSettings(enabled = false, preview = true, sound = NotifSound.CHORD),
        group = NotifTypeSettings(enabled = true, preview = false, sound = NotifSound.NONE),
        badge = BadgeSettings(includeMuted = true),
    )

    @Test
    fun `encode 顶层三个键，private group badge`() {
        val o = NotifySettingsWire.encode(fields)
        assertEquals(setOf("private", "group", "badge"), o.keys)
    }

    @Test
    fun `badge 用 snake_case include_muted，不是 includeMuted`() {
        val o = NotifySettingsWire.encode(fields)
        val badge = o.getValue("badge").jsonObject
        assertEquals(setOf("include_muted"), badge.keys)
        assertTrue(badge.getValue("include_muted").jsonPrimitive.boolean)
    }

    @Test
    fun `sound 编成 wire 字符串，不是枚举名`() {
        val o = NotifySettingsWire.encode(fields)
        assertEquals("chord", o.getValue("private").jsonObject.getValue("sound").jsonPrimitive.content)
        assertEquals("none", o.getValue("group").jsonObject.getValue("sound").jsonPrimitive.content)
    }

    @Test
    fun `encode 再 decode 原样往返`() {
        val roundTripped = NotifySettingsWire.decode(NotifySettingsWire.encode(fields))
        assertEquals(fields, roundTripped)
    }

    @Test
    fun `decode 时未知 sound 值回落 default`() {
        val o = buildJsonObject {
            put("private", buildJsonObject { put("enabled", true); put("preview", true); put("sound", "xylophone") })
            put("group", buildJsonObject { put("enabled", true); put("preview", true); put("sound", "chime") })
            put("badge", buildJsonObject { put("include_muted", false) })
        }
        val decoded = NotifySettingsWire.decode(o)
        assertEquals(NotifSound.DEFAULT, decoded.private.sound)
        assertEquals(NotifSound.CHIME, decoded.group.sound)
    }

    @Test
    fun `decode 时缺字段各自回落默认，不拖累其它字段`() {
        val o = buildJsonObject {
            put("private", buildJsonObject { put("enabled", false) }) // 缺 preview/sound
            // 缺整个 group
            put("badge", buildJsonObject { put("include_muted", true) })
        }
        val decoded = NotifySettingsWire.decode(o)
        assertFalse(decoded.private.enabled)
        assertTrue("缺 preview 回落默认 true", decoded.private.preview)
        assertEquals("缺 sound 回落 default", NotifSound.DEFAULT, decoded.private.sound)
        assertEquals("缺整个 group 回落出厂默认", NotifTypeSettings(), decoded.group)
        assertTrue(decoded.badge.includeMuted)
    }
}
