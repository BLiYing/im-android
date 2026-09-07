package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.DeviceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 已登录设备的展示口径（对齐 iOS `IMDeviceModels`）。 */
class DeviceDisplayTest {

    private fun dev(
        sid: String = "s1",
        platform: String = "ios",
        name: String = "李默的 iPhone",
        version: String = "",
        ip: String = "",
        loc: String = "",
        createdAt: Long = 0,
        lastActive: Long = 0,
        online: Boolean = false,
        current: Boolean = false,
    ) = DeviceSession(
        sessionId = sid, platform = platform, deviceName = name, appVersion = version,
        loginIp = ip, loginLoc = loc, createdAt = createdAt, lastActiveAt = lastActive,
        online = online, current = current,
    )

    @Test
    fun `平台图标与展示名逐项对齐 iOS`() {
        assertEquals("📱", DeviceDisplay.platformEmoji("ios"))
        assertEquals("🤖", DeviceDisplay.platformEmoji("android"))
        assertEquals("💻", DeviceDisplay.platformEmoji("web"))
        assertEquals("🖥", DeviceDisplay.platformEmoji("desktop"))
        // 未知平台不能落空串，否则那一格是空白
        assertEquals("📟", DeviceDisplay.platformEmoji("watchos"))

        assertEquals("iOS", DeviceDisplay.platformLabel("ios"))
        assertEquals("网页版", DeviceDisplay.platformLabel("web"))
        assertEquals("未知设备", DeviceDisplay.platformLabel(""))
    }

    @Test
    fun `设备名为空回退未知设备而不是空行`() {
        assertEquals("未知设备", DeviceDisplay.deviceName(dev(name = "")))
        assertEquals("未知设备", DeviceDisplay.deviceName(dev(name = "   ")))
        assertEquals("Chrome · macOS", DeviceDisplay.deviceName(dev(name = " Chrome · macOS ")))
    }

    @Test
    fun `相对活跃时间分四档`() {
        val now = 1_700_000_000_000L
        assertEquals("离线", DeviceDisplay.lastActiveText(0, now))
        assertEquals("刚刚活跃", DeviceDisplay.lastActiveText(now - 30_000, now))
        assertEquals("5 分钟前活跃", DeviceDisplay.lastActiveText(now - 5 * 60_000, now))
        assertEquals("3 小时前活跃", DeviceDisplay.lastActiveText(now - 3 * 3_600_000, now))
        assertEquals("2 天前活跃", DeviceDisplay.lastActiveText(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `设备时钟比服务端快时显示刚刚活跃而不是负数`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚活跃", DeviceDisplay.lastActiveText(now + 60_000, now))
        assertEquals("刚刚活跃", DeviceDisplay.lastActiveText(now + 86_400_000L, now))
    }

    @Test
    fun `在线状态行是 在线 · 平台 · 位置 · IP`() {
        val now = 1_700_000_000_000L
        val d = dev(platform = "ios", online = true, loc = "深圳 · 电信", ip = "113.88.1.2")
        assertEquals("在线 · iOS · 深圳 · 电信 · 113.88.1.2", DeviceDisplay.statusLine(d, now))
    }

    @Test
    fun `离线状态行以相对时间打头`() {
        val now = 1_700_000_000_000L
        val d = dev(online = false, lastActive = now - 3 * 86_400_000L, loc = "广州")
        assertEquals("3 天前活跃 · 广州", DeviceDisplay.statusLine(d, now))
    }

    @Test
    fun `缺字段不留空段`() {
        val now = 1_700_000_000_000L
        val d = dev(online = true, loc = "", ip = "")
        // 曾经的写法会拼出 "在线 · iOS ·  · "
        assertEquals("在线 · iOS", DeviceDisplay.statusLine(d, now))
        assertFalse(DeviceDisplay.statusLine(d, now).contains(" ·  · "))
    }

    @Test
    fun `类型行带版本号才拼 v`() {
        assertEquals("iOS", DeviceDisplay.typeText(dev(version = "")))
        assertEquals("iOS · v1.2.3", DeviceDisplay.typeText(dev(version = "1.2.3")))
    }

    @Test
    fun `登录时间无值回破折号`() {
        assertEquals("—", DeviceDisplay.loginTimeText(0))
        // 固定时区断言，否则这条测试在 CI 与本机会给出不同结果
        assertEquals(
            "2026-08-14 05:12",
            DeviceDisplay.loginTimeText(1786655520000, ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun `本机置顶单独一组其余一组`() {
        val me = dev(sid = "me", current = true)
        val other = dev(sid = "o1")
        val s = DeviceDisplay.sections(listOf(other, me))
        assertEquals(listOf("这台设备", "其他设备"), s.map { it.title })
        assertEquals(listOf("me"), s[0].devices.map { it.sessionId })
        assertEquals(listOf("o1"), s[1].devices.map { it.sessionId })
    }

    @Test
    fun `只有本机时不出现空的其他设备分组`() {
        val s = DeviceDisplay.sections(listOf(dev(sid = "me", current = true)))
        assertEquals(listOf("这台设备"), s.map { it.title })
        assertFalse(DeviceDisplay.canRevokeOthers(listOf(dev(sid = "me", current = true))))
    }

    @Test
    fun `后端没标本机时用中性标题而不是谎称其他设备`() {
        // current 全 false：不能挑一行当本机，也不能把这些叫「其他设备」——
        // 那会诱导用户去踢自己那一行
        val s = DeviceDisplay.sections(listOf(dev(sid = "a"), dev(sid = "b")))
        assertEquals(listOf("已登录设备"), s.map { it.title })
        assertEquals(2, s[0].devices.size)
    }

    @Test
    fun `空列表不产生任何分组`() {
        assertTrue(DeviceDisplay.sections(emptyList()).isEmpty())
    }
}
