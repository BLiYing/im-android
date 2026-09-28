package com.libeyond.imandroid.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.libeyond.imandroid.sdk.logging.IMLog
import java.io.File
import java.nio.ByteBuffer

/**
 * 录音分段拼接（设计 §5.3「继续时拼接同一段音频，不产生多文件」，iOS `exportMergedURL:`）。
 *
 * 为什么是多段：暂停时必须把当前段**真正收尾**（写好 moov），暂停中才能试听——
 * `MediaRecorder.pause()` 不收尾，文件在 stop 之前打不开（iOS 用 `AVAudioRecorder.pause` 踩过同一个坑）。
 * 所以每次暂停 = 一段，发送 / 试听时把各段 AAC 采样按顺序、时间戳顺延地写进同一个 MP4。
 * 各段来自同一组编码参数（16kHz 单声道 AAC），直接复用首段的轨道格式，无需重编码。
 */
object VoiceSegments {
    private val log = IMLog.tag("IM.Voice")

    /** 合并成功返回 true；任何一段读不出音轨 / 写失败 → false，并删掉半成品（**不拿最后一段顶替**，见 iOS 注释）。 */
    fun merge(segments: List<File>, out: File): Boolean {
        if (segments.isEmpty()) return false
        out.delete()
        var muxer: MediaMuxer? = null
        return try {
            val mx = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            var track = -1
            var offsetUs = 0L
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            for (seg in segments) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(seg.path)
                    val idx = (0 until ex.trackCount).firstOrNull {
                        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
                    } ?: error("no audio track")
                    ex.selectTrack(idx)
                    if (track < 0) {
                        track = mx.addTrack(ex.getTrackFormat(idx))
                        mx.start()
                    }
                    var lastUs = 0L
                    while (true) {
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        lastUs = ex.sampleTime
                        info.set(0, size, offsetUs + lastUs, ex.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        mx.writeSampleData(track, buf, info)
                        ex.advance()
                    }
                    // 下一段顺延到本段末尾之后一帧（AAC 一帧 1024 采样，16kHz ≈ 64ms）
                    offsetUs += lastUs + AAC_FRAME_US
                } finally {
                    ex.release()
                }
            }
            mx.stop()
            true
        } catch (e: Exception) {
            log.w("voice_merge_failed", "segments" to segments.size, "err" to (e.message ?: e.javaClass.simpleName))
            out.delete()
            false
        } finally {
            runCatching { muxer?.release() }
        }
    }

    private const val AAC_FRAME_US = 64_000L
}
