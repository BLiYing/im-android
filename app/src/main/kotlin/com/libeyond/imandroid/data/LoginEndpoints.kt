package com.libeyond.imandroid.data

import com.libeyond.imandroid.rtc.RtcConfig
import java.net.URI

/**
 * 登录页「长按 Logo ▸ 配置地址」弹窗的校验判据（纯函数，便于单测）。对照 iOS `IMLoginViewController`
 * 的 `saveEndpointHost:rtc:`：两项都合法才落盘，留空 = 恢复默认。
 *
 * 与 iOS 的差异：本端协议（http / https）由构建决定（`BuildConfig.USE_TLS`），服务器地址只存 `host:port`。
 * 因此允许带**与构建一致**的协议前缀（复制粘贴常带上），会被剥掉；协议不一致直接判非法，免得误以为切换了协议。
 */
object LoginEndpoints {

    sealed interface Check {
        /** 两项都合法。null = 留空，恢复默认。 */
        data class Ok(val host: String?, val rtcWsUrl: String?) : Check
        data object HostInvalid : Check
        data object RtcInvalid : Check
    }

    fun check(hostText: String, rtcText: String, useTls: Boolean): Check {
        val hostTrimmed = hostText.trim()
        val rtcTrimmed = rtcText.trim()
        val host = if (hostTrimmed.isEmpty()) null else normalizedHost(hostTrimmed, useTls) ?: return Check.HostInvalid
        val rtc = if (rtcTrimmed.isEmpty()) null else RtcConfig.normalizedWsUrl(rtcTrimmed) ?: return Check.RtcInvalid
        return Check.Ok(host, rtc)
    }

    /** `host[:port]`，可带与构建一致的协议前缀与尾斜杠；不支持路径。非法返回 null。 */
    fun normalizedHost(input: String, useTls: Boolean): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val sep = s.indexOf("://")
        if (sep >= 0) {
            if (s.substring(0, sep).lowercase() != (if (useTls) "https" else "http")) return null
            s = s.substring(sep + 3)
        }
        s = s.trimEnd('/')
        // 路径 / 查询 / 用户信息一律拒：全端都按 `host + 绝对路径` 拼地址
        if (s.isEmpty() || s.endsWith(":") || s.any { it in "/?#@" }) return null
        val uri = runCatching { URI("http://$s") }.getOrNull() ?: return null
        return if (uri.host.isNullOrEmpty()) null else s
    }
}
