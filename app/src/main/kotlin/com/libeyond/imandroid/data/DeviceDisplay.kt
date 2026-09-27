package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.DeviceSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 已登录设备的**纯展示口径**——图标、名字兜底、状态行、分区。
 *
 * 语义（谁在线、能不能踢）全在服务端；本模块只把 [DeviceSession] 映射成一行的图标与两行文本。
 * 抽成纯函数是为了**可变异验证**：改一处文案/分支立刻有测试变红（CODING_STYLE §7③）。
 *
 * 对称兄弟：iOS `IMDeviceModels.m`、Web `src/devices.ts`。
 * **口径按 iOS**（本次以 iOS 为基准实现），与 Web 已知的两处差异记在 [statusLine] 注释里。
 */
object DeviceDisplay {

    /** 平台 → 行首 emoji。未知平台回退通用终端图标（三端同一套 emoji）。 */
    fun platformEmoji(platform: String): String = when (platform) {
        "ios" -> "📱"
        "android" -> "🤖"
        "web" -> "💻"
        "desktop" -> "🖥"
        else -> "📟"
    }

    /** 平台展示名。 */
    fun platformLabel(platform: String): String = when (platform) {
        "ios" -> "iOS"
        "android" -> "Android"
        "web" -> Str.s(R.string.device_platform_web)
        "desktop" -> Str.s(R.string.device_platform_desktop)
        else -> Str.s(R.string.device_platform_unknown)
    }

    /**
     * 设备名。空则回退「未知设备」——**按 iOS**。
     * Web 在这里按平台兜底成「iOS 设备 / 网页版…」，是已知的三端文案差异，不是 bug；
     * 本端跟 iOS，是因为本次「我」页整体以 iOS 为基准。
     */
    fun deviceName(d: DeviceSession): String = d.deviceName.trim().ifBlank { Str.s(R.string.device_platform_unknown) }

    /**
     * 最近活跃的相对文案。`ms <= 0` 视为从未活跃过 → 「离线」。
     *
     * 设备时钟比服务端快时 `now - lastActiveAt` 会是负数；钳到 0 后落进第一档「刚刚活跃」。
     * （不钳其实也落同一档——负数同样 `< 60`。留着钳位是为了将来加档位时不被负数穿透，
     * 它对当前输出不可观测，所以测试断言的是**输出**「刚刚活跃」，不是钳位本身。）
     */
    fun lastActiveText(lastActiveAt: Long, now: Long): String {
        if (lastActiveAt <= 0) return Str.s(R.string.common_offline)
        val sec = ((now - lastActiveAt) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> Str.s(R.string.device_active_just_now)
            sec < 3600 -> Str.p(R.plurals.device_active_minutes_ago, (sec / 60).toInt(), (sec / 60).toInt())
            sec < 86400 -> Str.p(R.plurals.device_active_hours_ago, (sec / 3600).toInt(), (sec / 3600).toInt())
            else -> Str.p(R.plurals.device_active_days_ago, (sec / 86400).toInt(), (sec / 86400).toInt())
        }
    }

    /**
     * 列表行副标题：在线→「在线 · iOS · 位置 · IP」；离线→「3 天前活跃 · 位置 · IP」。
     * 缺字段自动省略（**不留空的 ` · · `**）。圆点由 UI 上色绘制，不进这串文本。
     *
     * 与 Web 的差异（已知，不修）：Web 在线时第二段放相对时间而不是平台名，且离线时不带 IP。
     */
    fun statusLine(d: DeviceSession, now: Long): String {
        val parts = mutableListOf<String>()
        if (d.online) {
            parts += Str.s(R.string.common_online)
            parts += platformLabel(d.platform)
        } else {
            parts += lastActiveText(d.lastActiveAt, now)
        }
        d.loginLoc.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        d.loginIp.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** 详情页「类型」行：「iOS · v1.2.3」；没有版本号就只有平台名。 */
    fun typeText(d: DeviceSession): String =
        if (d.appVersion.isBlank()) platformLabel(d.platform)
        else "${platformLabel(d.platform)} · v${d.appVersion}"

    /** 登录时间绝对文案 `2026-08-13 09:12`；无值回 `—`。 */
    fun loginTimeText(createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (createdAt <= 0) return "—"
        return LOGIN_TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(createdAt))
    }

    private val LOGIN_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 一个分区：标题 + 行。 */
    data class Section(val title: String, val devices: List<DeviceSession>)

    /**
     * 列表分区（逐字对齐 iOS `applyDevices:`）：本机置顶单独一组，其余一组。
     *
     * **后端没标出本机时不谎称「其他设备」**——用中性标题「已登录设备」。
     * 把本机混在「其他设备」里，会诱导用户去踢自己那一行（iOS 踩过，注释留在那）。
     * 同理，`current` 全为 false 时**不能**挑一行当本机（[DeviceSession.current] 的注释）。
     */
    fun sections(devices: List<DeviceSession>): List<Section> {
        val current = devices.firstOrNull { it.current }
        val others = devices.filter { it !== current }
        val thisDevice = Str.s(R.string.device_list_section_this_device)
        return when {
            current != null && others.isEmpty() -> listOf(Section(thisDevice, listOf(current)))
            current != null -> listOf(Section(thisDevice, listOf(current)), Section(Str.s(R.string.device_list_section_other_devices), others))
            others.isNotEmpty() -> listOf(Section(Str.s(R.string.settings_row_devices), others))
            else -> emptyList()
        }
    }

    /** 有别的设备才显示「退出其他所有设备」——只有本机时那个按钮点了什么也不会发生。 */
    fun canRevokeOthers(devices: List<DeviceSession>): Boolean = devices.any { !it.current }
}
