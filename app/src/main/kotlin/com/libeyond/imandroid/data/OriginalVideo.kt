package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 查看器里那枚「查看原视频」胶囊的判据（对齐 iOS `IMMediaViewerViewController` 的 `_originalChip`
 * + `IMOriginalVideoCache`）。
 *
 * ### 它解决什么
 * 视频没下到本地时查看器是**流式**播的：拖进度要等、弱网卡顿、断网直接放不了。
 * iOS 给一枚胶囊让用户主动把原件拉下来，下完切本地播放；本端此前没有这一步，
 * 用户只能退出去在气泡上点 ↓。
 *
 * ### 为什么抽成纯函数
 * 这一族错得静默：胶囊在**已经有本地原件**时还显，用户点了等于白下一遍；
 * 或者下载中不显百分比，看着像卡住。判据只有几行，但它读的是"下载状态 × 有没有本地件"两件事，
 * 摆在 composable 里就没人测得到。
 */
object OriginalVideo {

    /**
     * 胶囊显示什么文案；`null` = 不显示这枚胶囊。
     *
     * @param isVideo    只有视频有这枚胶囊（图片本来就整张加载）。
     * @param hasLocal   本地已有原件（下载器 `localFile != null`）。**有就不显**——
     *                   iOS 的注释写着用户反馈：看过原视频后重进不该再问一次。
     * @param phase      下载状态机的阶段。
     * @param fraction   0..1 的进度；`hasPercent` 为假时传 0。
     * @param hasPercent 总大小已知（能显百分比）。
     * @param sizeBytes  原件字节数（消息元数据里的 `file_size`），>0 时显示在文案里，
     *                   让用户在点之前知道这一下要花多少流量。
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun chipLabel(
        isVideo: Boolean,
        hasLocal: Boolean,
        phase: DownloadPhase,
        fraction: Float,
        hasPercent: Boolean,
        sizeBytes: Long,
    ): String? {
        if (!isVideo || hasLocal) return null
        return when (phase) {
            // 失效是终态：服务端已经清理，点了只会一次次拉 404（MediaDownloader 纪律 2）
            DownloadPhase.Expired -> null
            // Ready 但 hasLocal=false：缓存被清了/被系统回收了，当没下过处理
            DownloadPhase.Downloading ->
                if (hasPercent) {
                    Str.s(R.string.chat_media_downloading_progress, (fraction * 100).toInt())
                } else {
                    Str.s(R.string.chat_media_downloading)
                }
            DownloadPhase.Failed -> Str.s(R.string.media_download_failed_tap_retry)
            else -> if (sizeBytes > 0) {
                Str.s(R.string.chat_media_view_original_sized, MediaUrl.formatSize(sizeBytes))
            } else {
                Str.s(R.string.chat_media_view_original)
            }
        }
    }

    /** 点这枚胶囊要不要真的发起下载（下载中再点是 no-op——**本端没有断点续传，别当暂停用**）。 */
    fun tapStartsDownload(phase: DownloadPhase): Boolean = phase != DownloadPhase.Downloading

    /**
     * 下载表里**已经就绪**的那些地址。
     *
     * ### 它是干什么的：让查看器在"下完的那一刻"重算一次
     * [chipLabel] 的 `hasLocal` 与播放器用的本地文件都是查看器**在重组时现问下载器**的，
     * 而下载器的状态流在别处。查看器不重组 → 这两件事都停在下载开始前的答案：
     * 胶囊一直显着「查看原视频」（2026-09-16 用户报：下完了胶囊没消失，重进才消失），
     * 播放器也继续流式播那份远端的，白下一遍。
     *
     * **为什么不直接把整张状态表读进查看器**：那张表在下载期间**每 64KB 就发一次**
     * （`MediaDownloader.report`）。整个查看器（含可缩放大图与 ExoPlayer 容器）跟着每秒重组几十次，
     * 换来的是比原先更明显的卡顿。只取"谁已就绪"这个集合，它在**下载完成那一刻才变一次**，
     * 进度推进完全不触发重组；百分比文案由胶囊自己那一层单独 collect。
     */
    fun readyUrls(states: Map<String, DownloadState>): Set<String> =
        states.asSequence().filter { it.value.phase == DownloadPhase.Ready }.map { it.key }.toSet()
}
