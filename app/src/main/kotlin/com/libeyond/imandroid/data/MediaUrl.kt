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
        // **已经带 scheme 的一律原样用**，只有服务端那种相对路径才补 host。
        //
        // `content://` 与 `file://` 必须在这里挡住：待发消息的正文是相册的
        // `content://media/...`，已下载媒体是沙盒里的 `file://...`——拼上 host 会得到
        // `http://10.0.2.2:8080/content://media/…` 这种谁也加载不了的东西，
        // 表现是「选完图宫格里那几格是空的」。2026-09-08 实测撞见：
        // 调用点各自 `startsWith("content://")` 挡一次（MediaViewerScreen / VideoPlayer 有，
        // AlbumBubble 没有）——**判据散在调用点就一定会漏一处**，收进这里。
        if (raw.startsWith("http://") || raw.startsWith("https://") ||
            raw.startsWith("data:") || raw.startsWith("content://") || raw.startsWith("file://")
        ) {
            return raw
        }
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
