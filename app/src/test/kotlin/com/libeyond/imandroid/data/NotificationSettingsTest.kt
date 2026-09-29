package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NotificationSettingsCodec] 编解码与逐键回落；[NotificationSettings.withType]/[typeOf] 的读写对偶。 */
class NotificationSettingsTest {

    @Test
    fun `空表回落全部默认值`() {
        val s = NotificationSettingsCodec.decode(emptyMap())
        assertEquals(NotificationSettings.DEFAULT, s)
    }

    @Test
    fun `编码后解码原样往返`() {
        val s = NotificationSettings(
            private = NotifTypeSettings(enabled = false, preview = false, sound = NotifSound.CHORD),
            group = NotifTypeSettings(enabled = true, preview = false, sound = NotifSound.NONE),
            inApp = InAppSettings(sound = false, vibrate = true, preview = false),
            badge = BadgeSettings(includeMuted = true),
        )
        val roundTripped = NotificationSettingsCodec.decode(NotificationSettingsCodec.encode(s))
        // desktop 不参与编解码（Android 不持久化，见 NotificationSettings 类注释），单独核对其余字段
        assertEquals(s.private, roundTripped.private)
        assertEquals(s.group, roundTripped.group)
        assertEquals(s.inApp, roundTripped.inApp)
        assertEquals(s.badge, roundTripped.badge)
    }

    @Test
    fun `单个键损坏只影响那一个字段——不拖累其它字段回落`() {
        val raw = NotificationSettingsCodec.encode(
            NotificationSettings(
                private = NotifTypeSettings(enabled = false, sound = NotifSound.DROP),
                badge = BadgeSettings(includeMuted = true),
            ),
        ).toMutableMap()
        raw["private.enabled"] = "not-a-boolean" // 坏值
        raw["private.sound"] = "made-up-id" // 未知 id
        val s = NotificationSettingsCodec.decode(raw)
        assertTrue("坏值回落默认 true", s.private.enabled)
        assertEquals("未知 id 回落 default", NotifSound.DEFAULT, s.private.sound)
        assertTrue("同表里没坏的键不受影响", s.badge.includeMuted)
    }

    @Test
    fun `NotifSound 未知 id 回落 default`() {
        assertEquals(NotifSound.DEFAULT, NotifSound.fromWire("xylophone"))
        assertEquals(NotifSound.DEFAULT, NotifSound.fromWire(null))
        assertEquals(NotifSound.NONE, NotifSound.fromWire("none"))
        assertEquals(NotifSound.DROP, NotifSound.fromWire("drop"))
    }

    @Test
    fun `withType 只改对应那一类，另一类原样保留`() {
        val s = NotificationSettings.DEFAULT
        val changed = s.withType(group = true) { it.copy(enabled = false) }
        assertFalse(changed.group.enabled)
        assertTrue("private 不受影响", changed.private.enabled)
        assertEquals(changed.group, changed.typeOf(group = true))
        assertEquals(changed.private, changed.typeOf(group = false))
    }
}
