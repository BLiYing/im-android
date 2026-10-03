package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 一条待发媒体当前的**传输状态**（对齐 iOS `IMUploadProgress`）。只活在内存，随进程消亡。
 * 「失败」不在这里——失败是待发行的 `state`（红 ❗ / 说明行），本类只描述还在路上的那几种。
 */
data class UploadState(
    val phase: Phase,
    val sent: Long = 0,
    val total: Long = 0,
    /** 能暂停（走分片的那条路）。一次性整包上传不可中断，不出 ⏸ 钮。 */
    val pausable: Boolean = false,
    /** 用户暂停了。 */
    val paused: Boolean = false,
) {
    enum class Phase { Queued, Uploading }

    /** 按 [sent]/[total] 的百分比（向下取整，到 100 只在真传完）。 */
    val percent: Int get() = UploadProgress.percent(sent, total)

    /** 环形进度 0..1（失败时调用方自行置 0）。 */
    val fraction: Float get() = if (total <= 0L) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)

    /** 气泡/宫格格子上的控制钮该画什么（对应 iOS 的 ✕ / ⏸ / ↑ / 无）。 */
    val control: Control
        get() = when {
            phase == Phase.Queued -> Control.Cancel
            pausable && paused -> Control.Resume
            pausable -> Control.Pause
            else -> Control.None
        }

    enum class Control { Cancel, Pause, Resume, None }
}

/** 上传状态的文案（纯函数，可单测）。 */
object UploadStateText {
    /**
     * 媒体气泡**左上角**的小标：排队 = 「等待中」；传输中 = 「已传 / 总大小」（如 `3.9 MB / 12.4 MB`，
     * 刻意不写「已传」二字，读起来歧义）；比例为 0 = 「等待中」；总大小未知 = 「N%」。暂停沿用同一串（⏸ 符号另画）。
     */
    fun mediaBadge(s: UploadState): String = when {
        s.phase == UploadState.Phase.Queued -> Str.s(R.string.media_upload_waiting)
        s.sent <= 0L -> Str.s(R.string.media_upload_waiting)
        s.total <= 0L -> "${s.percent}%"
        else -> bytesLine(s.sent, s.total)
    }

    /** 文件气泡第二行：排队 = 「准备中…」；传输中/暂停 = `X / Y`；0 字节时也先显示「准备中…」。 */
    fun fileLine(s: UploadState): String = when {
        s.phase == UploadState.Phase.Queued || s.total <= 0L -> Str.s(R.string.media_upload_preparing)
        else -> bytesLine(s.sent, s.total)
    }

    fun bytesLine(sent: Long, total: Long): String = "${MediaUrl.formatSize(sent)} / ${MediaUrl.formatSize(total)}"
}
