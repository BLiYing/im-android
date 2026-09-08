package com.libeyond.imandroid.data

/**
 * 媒体下载状态机（M4-7）——与 iOS `IMDownloadProgress` / Web `DownloadPhase` **五态镜像**。
 *
 * 为什么要有「暂停」与「失效」这两个态：
 * - **暂停**：用户点了 ⏸。它和「没开始」显示不同（⏸ 停在环上 vs 一个 ↓），
 *   合并成一个态的话，用户点了暂停会看到进度归零，以为白下了。
 * - **失效**：服务端已清理（404/410）。它和「失败」显示不同——**失效不给重试**，
 *   给了也是每点一次拉一次 404。iOS 为此专门有 `IMMediaExpiryRegistry`。
 */
enum class DownloadPhase {
    /** 没开始（门控判否，或用户从没点过）。显示 ↓ + 大小。 */
    NotStarted,

    /** 下载中。显示环形进度 + ⏸。 */
    Downloading,

    /** 用户暂停。显示 ↓（点了从头再来——本端不做断点续传，见 KDoc）。 */
    Paused,

    /** 失败（网络错）。显示 ↻，可重试。 */
    Failed,

    /** 服务端已清理。显示 ⊘ +「文件已失效」，**不可重试**。 */
    Expired,

    /** 本地已就绪。 */
    Ready,
}

/**
 * @param received 已收字节
 * @param total 总字节；`0` = 未知（服务端没给 `Content-Length`），此时只能显示「在动」而没有百分比
 */
data class DownloadState(
    val phase: DownloadPhase = DownloadPhase.NotStarted,
    val received: Long = 0,
    val total: Long = 0,
) {
    /** 0..1；总大小未知时回退 0（环只能转，不能显示进度）。 */
    val fraction: Float
        get() = if (total <= 0) 0f else (received.toFloat() / total).coerceIn(0f, 1f)

    /** 有没有确切的百分比可显示。 */
    val hasPercent: Boolean get() = total > 0

    val isBusy: Boolean get() = phase == DownloadPhase.Downloading

    /** 点一下该做什么。**失效是终态**，点了不做事（不然就是每点一次拉一次 404）。 */
    fun tapAction(): DownloadTap = when (phase) {
        DownloadPhase.NotStarted, DownloadPhase.Paused, DownloadPhase.Failed -> DownloadTap.Start
        DownloadPhase.Downloading -> DownloadTap.Pause
        DownloadPhase.Ready -> DownloadTap.Open
        DownloadPhase.Expired -> DownloadTap.None
    }
}

enum class DownloadTap { Start, Pause, Open, None }

/**
 * 文件行/文件气泡上跟在大小后面的那句状态。
 *
 * **气泡与详情页的文件行共用这一份**——两处各写一张表，迟早出现「气泡说下载失败、
 * 详情页说未下载」。就绪态返回空串：给已经下好的东西标一句"已下载"只是噪声
 * （iOS 详情行会写「· 已下载」，本端气泡侧不写，两处保持一致即可）。
 */
fun DownloadPhase.fileHint(): String = when (this) {
    DownloadPhase.Ready -> ""
    DownloadPhase.Downloading -> " · 下载中"
    DownloadPhase.Paused -> " · 已暂停，点继续"
    DownloadPhase.Failed -> " · 下载失败，点重试"
    DownloadPhase.Expired -> " · 文件已失效"
    DownloadPhase.NotStarted -> " · 未下载"
}
