package com.libeyond.imandroid.data

/**
 * 媒体地址解析。
 *
 * 服务端存的 `content` 对媒体消息是**相对路径** `/uploads/xxx`——
 * 服务端不知道端可达的 host（真机连局域网 IP 时尤其），所以不绝对化。
 * 端上要按自己连的那个 host 补全（同一条约定，邀请卡的头像 URL 也这么处理）。
 */
object MediaUrl {

    /**
     * 把消息里的地址补全成可加载的绝对 URL。
     *
     * @param raw 消息 content 或 avatar_url
     * @param host 形如 `10.0.2.2:8080`
     * @param useTls
     */
    fun absolute(raw: String, host: String, useTls: Boolean): String {
        if (raw.isBlank()) return ""
        // 已是绝对地址或 data URI → 原样用
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
        val scheme = if (useTls) "https" else "http"
        val path = if (raw.startsWith("/")) raw else "/$raw"
        return "$scheme://$host$path"
    }

    /** 从 `/uploads/req-xxx__原始名.ext` 里取出给人看的文件名。 */
    fun displayFileName(raw: String, fallback: String = ""): String {
        if (fallback.isNotBlank()) return fallback
        val last = raw.substringAfterLast('/')
        // 服务端的命名是 `req-<id>__<原始名>`，双下划线后才是原始名
        val afterMarker = last.substringAfter("__", last)
        return afterMarker.ifBlank { last }
    }

    /** 字节数格式化。**不为了展示去重新下载文件算大小**（PROTOCOL §4.3 明文）。 */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** 毫秒时长 → `m:ss`。 */
    fun formatDuration(ms: Int?): String {
        val total = ((ms ?: 0) / 1000).coerceAtLeast(0)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
