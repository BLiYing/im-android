package com.libeyond.imandroid.data

import kotlin.math.roundToLong

/**
 * 未下载媒体上**显示什么字、画哪个图标**（M4-7 门控 UI）。逐条照 iOS 源码（2026-09-10 核实）：
 * `IMDownloadProgress` 的 `displayText` / `fileLineText`、`IMFormatUploadProgress`、
 * `IMDownloadCenterSymbolName`、`IMImageCell.renderGatedDownloadUI`、`IMAlbumTileView.setDownloadState:`、
 * `IMBubbleCell` 的文件图标位。
 *
 * 抽成纯函数的理由：这几张表错了不报错，只是某个状态下显示了另一个状态的字
 * （2026-09-10 用户报的 #5/#6/#7——未下载的图片、视频、文件各状态与 iOS 对不上）。
 * 画的那一半在 `ui/components/GateOverlays.kt`。
 *
 * **与 iOS 的一处刻意差异**：本端暂停会删掉半截文件（没有续传，见 [MediaDownloader.pause]），
 * 暂停后已收字节恒为 0。照 iOS 写「已下 / 总」只会显示成「等待中」，像还在排队——所以暂停显「已暂停」。
 */
object DownloadLabels {

    /** 状态图标。图片/视频画在实心圆钮里，宫格一格画裸字形。 */
    enum class Glyph { Download, Pause, Retry, None }

    /** iOS `IMDownloadCenterSymbolName`：未开始/暂停 ↓、下载中 ⏸、失败 ↻；就绪不画，失效另有 ⊘ 覆盖层。 */
    fun glyphOf(phase: DownloadPhase): Glyph = when (phase) {
        DownloadPhase.NotStarted, DownloadPhase.Paused -> Glyph.Download
        DownloadPhase.Downloading -> Glyph.Pause
        DownloadPhase.Failed -> Glyph.Retry
        DownloadPhase.Ready, DownloadPhase.Expired -> Glyph.None
    }

    /** 进度环只在下载中 / 暂停时画。 */
    fun showsRing(phase: DownloadPhase): Boolean =
        phase == DownloadPhase.Downloading || phase == DownloadPhase.Paused

    /** 环的填充比例：0% 也露一点头（iOS `MAX(0.02, fraction)`），否则看不出"在动"。 */
    fun ringFraction(state: DownloadState, sizeBytes: Long): Float = maxOf(MIN_RING, fractionOf(state, sizeBytes))

    /** 总大小优先用下载时拿到的；服务端没回 `Content-Length` 时用消息上的 `file_size`。 */
    private fun totalOf(state: DownloadState, sizeBytes: Long): Long = if (state.total > 0) state.total else sizeBytes

    private fun fractionOf(state: DownloadState, sizeBytes: Long): Float {
        val total = totalOf(state, sizeBytes)
        return if (total <= 0) 0f else (state.received.toFloat() / total).coerceIn(0f, 1f)
    }

    /** iOS `IMFormatUploadProgress`：还没收到字节「等待中」，否则「已下 / 总」。暂停见类注释。 */
    fun progressText(state: DownloadState, sizeBytes: Long): String {
        if (state.phase == DownloadPhase.Paused) return "已暂停"
        val total = totalOf(state, sizeBytes)
        // 两边都不知道总大小：iOS 不会走到这里（它恒有 total），本端给已收多少，总比「等待中」真
        if (total <= 0) return if (state.received > 0) MediaUrl.formatSize(state.received) else "等待中"
        val f = fractionOf(state, sizeBytes)
        if (f <= 0f) return "等待中"
        val sent = (f.toDouble() * total).roundToLong()
        return "${MediaUrl.formatSize(sent)} / ${MediaUrl.formatSize(total)}"
    }

    /** iOS `displayText`：未开始显大小、下载中/暂停显进度、失败/失效显文案、就绪空串。 */
    fun displayText(state: DownloadState, sizeBytes: Long): String = when (state.phase) {
        DownloadPhase.NotStarted -> MediaUrl.formatSize(sizeBytes)
        DownloadPhase.Downloading, DownloadPhase.Paused -> progressText(state, sizeBytes)
        DownloadPhase.Failed -> "下载失败"
        DownloadPhase.Expired -> "文件已失效"
        DownloadPhase.Ready -> ""
    }

    /**
     * 图片/视频左上角**唯一一块**胶囊；null = 不显。
     *
     * 未下载 =「大小 · 时长」（大小在前）；下载中/暂停/失败 = **只显状态、藏时长**——两项并排在窄图上会溢出。
     * 就绪不显（视频的时长胶囊回来）；失效不显（整块失效覆盖层接管）。
     */
    fun mediaCapsule(state: DownloadState, sizeBytes: Long, durationText: String?): String? {
        val text = when (state.phase) {
            DownloadPhase.Ready, DownloadPhase.Expired -> return null
            DownloadPhase.NotStarted ->
                listOf(MediaUrl.formatSize(sizeBytes), durationText.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
            else -> displayText(state, sizeBytes)
        }
        return text.ifEmpty { null }
    }

    /** 胶囊底色：失败换危险色（iOS `_progressWrap.backgroundColor`）。 */
    fun capsuleIsDanger(phase: DownloadPhase): Boolean = phase == DownloadPhase.Failed

    /** 宫格一格的角标：格子只有 ~79dp 宽，**只放一项**——未下载显大小，其余显状态；失效不显。 */
    fun tileCaption(state: DownloadState, sizeBytes: Long): String? = when (state.phase) {
        DownloadPhase.Ready, DownloadPhase.Expired -> null
        else -> displayText(state, sizeBytes).ifEmpty { null }
    }

    /** 文件气泡名字下面那行（iOS `IMBubbleCell` 文件状态行，底层是 `fileLineText`）。 */
    fun fileStatusLine(state: DownloadState, sizeBytes: Long): String {
        val size = MediaUrl.formatSize(sizeBytes)
        return when (state.phase) {
            DownloadPhase.NotStarted -> if (size.isEmpty()) "点击下载" else "$size · 点击下载"
            DownloadPhase.Ready -> size
            DownloadPhase.Downloading, DownloadPhase.Paused -> progressText(state, sizeBytes)
            DownloadPhase.Failed -> "下载失败，点击重试"
            DownloadPhase.Expired -> "文件已失效"
        }
    }

    /**
     * **详情页 / 收藏页**文件行的状态副行（iOS `IMDetailFileCell.renderDownload:`），与聊天气泡那行
     * [fileStatusLine] **刻意是两张表**：iOS 两处本就不同——行上写「240 KB · 未下载」「1.3 MB · 已下载」，
     * 气泡上写「点击下载」。图标位与进度文案两处共用（[fileSlotOf] / [progressText]），只有这一行分开。
     *
     * 此前本端详情行是一张自造的表（「· 下载中」「· 已暂停，点继续」），下载中不显进度、
     * 与气泡那侧的状态字也对不上（2026-09-17 用户报：详情页的下载示意要复用聊天页那一套）。
     */
    fun archiveFileLine(state: DownloadState, sizeBytes: Long): String {
        val size = MediaUrl.formatSize(sizeBytes)
        return when (state.phase) {
            DownloadPhase.NotStarted -> if (size.isEmpty()) "未下载" else "$size · 未下载"
            DownloadPhase.Ready -> if (size.isEmpty()) "已下载" else "$size · 已下载"
            DownloadPhase.Downloading, DownloadPhase.Paused -> progressText(state, sizeBytes)
            DownloadPhase.Failed -> "下载失败，点击重试"
            DownloadPhase.Expired -> "文件已失效"
        }
    }

    /** 文件状态行标红：失败与失效（iOS 的失效是「失败 + expired」，同一个判据）。 */
    fun fileStatusIsDanger(phase: DownloadPhase): Boolean =
        phase == DownloadPhase.Failed || phase == DownloadPhase.Expired

    /** 文件图标位画什么。 */
    enum class FileSlot {
        /** 就绪：按扩展名的类型图标。**只有这一态才显**——没下下来时显类型图标，看着像已经能打开（#8）。 */
        TypeIcon,

        /** 未下载：强调色实心圆 + 白 ↓。 */
        StartDisc,

        /** 下载中：进度环 + ⏸。 */
        RingPause,

        /** 暂停：进度环 + ↓。 */
        RingResume,

        /** 失败：红 ↻。 */
        Retry,

        /** 失效：红 ⊘，不可点。 */
        Expired,
    }

    fun fileSlotOf(phase: DownloadPhase): FileSlot = when (phase) {
        DownloadPhase.Ready -> FileSlot.TypeIcon
        DownloadPhase.NotStarted -> FileSlot.StartDisc
        DownloadPhase.Downloading -> FileSlot.RingPause
        DownloadPhase.Paused -> FileSlot.RingResume
        DownloadPhase.Failed -> FileSlot.Retry
        DownloadPhase.Expired -> FileSlot.Expired
    }

    private const val MIN_RING = 0.02f
}
