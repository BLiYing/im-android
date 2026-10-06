package com.libeyond.mediapicker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** 视频的尺寸、时长与首帧封面。 */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Int,
)

/**
 * 视频元数据与首帧封面。
 *
 * ### 为什么必须出封面
 * 协议里的 `poster` 不是锦上添花：**解不了 HEVC 的浏览器根本显示不了 iPhone 拍的视频**，
 * 只能靠发送端给的封面 JPEG（PROTOCOL §4.1）。iOS 用 AVFoundation 首帧、Web 用 canvas 抓帧，
 * 本端用 [MediaMetadataRetriever]。不出封面 = 对端一片黑。
 *
 * ### 为什么取第 0.1 秒而不是第 0 秒
 * 很多设备第一帧是纯黑（曝光还没上来）。iOS 侧 `IMVideoThumbnailLoader` 就是取 0.1s，
 * 这里同口径。
 */
object VideoProbe {

    /** 抽帧位置（微秒）。与 iOS `IMVideoThumbnailLoader` 同为 0.1s。 */
    private const val FRAME_US = 100_000L

    /** 封面长边上限；封面只是给对端预览，不需要原分辨率。 */
    const val POSTER_MAX_EDGE = 720

    const val POSTER_QUALITY = 75

    /**
     * 一次 `setDataSource` 同时读元数据与首帧封面。必须在 IO 线程调用。
     *
     * 此前元数据与封面各开一个 `MediaMetadataRetriever`：每开一次都要重新解析容器头，
     * 视频在远端/云盘（`content://` 背后是网络）时是实打实的两次往返。
     * 封面失败不连累元数据（反之亦然）——两者各自独立兜底，宁可只拿到一半也别全丢。
     */
    fun probe(
        context: Context,
        uri: Uri,
        maxEdge: Int = POSTER_MAX_EDGE,
        log: MediaPickerLog = MediaPickerLog.None,
    ): Probe {
        var info: VideoInfo? = null
        var poster: ByteArray? = null
        retrieve(context, uri, log) { r ->
            val raw = readInfo(r)
            info = raw.display()
            poster = posterOf(r, raw, maxEdge, log)
            Unit
        }
        return Probe(info, poster)
    }

    class Probe(val info: VideoInfo?, val poster: ByteArray?)

    /**
     * **只读元数据**（宽高已按旋转换算、时长），不解码任何帧——毫秒级。必须在 IO 线程调用。
     *
     * 给「落待发行之前」用：行落库那一刻就要有显示尺寸，不然气泡先按默认比例画、探测完再跳成竖的
     * （竖拍视频恰恰是最常见的情形）。封面抽帧慢，仍留在 [probe] 里发送阶段再做。
     */
    fun info(context: Context, uri: Uri, log: MediaPickerLog = MediaPickerLog.None): VideoInfo? {
        var out: VideoInfo? = null
        retrieve(context, uri, log) { r ->
            out = readInfo(r).display()
            Unit
        }
        return out
    }

    /** 容器里存的宽高（**未**按旋转换算）+ 旋转角。 */
    private class RawInfo(val width: Int, val height: Int, val durationMs: Int, val rotation: Int) {
        /** 竖着拍的视频，宽高在容器里是横的、靠旋转标记转过来——不换的话对端按 16:9 预留气泡，实际是 9:16。 */
        fun display(): VideoInfo {
            val (w, h) = MediaCompressor.displaySize(width, height, rotation)
            return VideoInfo(w, h, durationMs)
        }
    }

    private fun readInfo(r: MediaMetadataRetriever) = RawInfo(
        width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
        height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0,
        rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
    )

    /**
     * API 27+ 的 `getScaledFrameAtTime` 在解码端直接出小图，4K 视频不用先解出一张 3840×2160 的位图再缩。
     * 返回要请求的尺寸；**null = 走「取全尺寸再缩」**：
     * - 低于 API 27 没有这个方法；
     * - 视频本来就不大于 [maxEdge]，缩不缩一样；
     * - **旋转 90/270 的不走**：目标宽高是按存储方向还是显示方向解释，各厂商/系统版本的行为我们没法在
     *   单测里钉死，传反了封面就被拉伸变形——竖拍视频恰恰是最常见的这一类，宁可保守走老路。
     */
    internal fun scaledFrameSize(width: Int, height: Int, rotation: Int, sdkInt: Int, maxEdge: Int): Pair<Int, Int>? {
        if (sdkInt < 27 || width <= 0 || height <= 0) return null
        if (rotation == 90 || rotation == 270) return null
        if (maxOf(width, height) <= maxEdge) return null
        return MediaCompressor.targetSize(width, height, maxEdge)
    }

    private fun posterOf(r: MediaMetadataRetriever, raw: RawInfo, maxEdge: Int, log: MediaPickerLog): ByteArray? = try {
        val dst = scaledFrameSize(raw.width, raw.height, raw.rotation, android.os.Build.VERSION.SDK_INT, maxEdge)
        val frame = (if (dst != null) scaledFrame(r, dst) else null)
            ?: r.getFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.frameAtTimeFallback()
        if (frame == null) {
            null
        } else {
            val (tw, th) = MediaCompressor.targetSize(frame.width, frame.height, maxEdge)
            val scaled = if (tw != frame.width || th != frame.height) {
                Bitmap.createScaledBitmap(frame, tw, th, true)
            } else {
                frame
            }
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, bos)
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            bos.toByteArray()
        }
    } catch (e: Exception) {
        log.w("video_poster_failed", "err" to e.javaClass.simpleName)
        null
    } catch (e: OutOfMemoryError) {
        log.w("video_poster_oom")
        null
    }

    private fun scaledFrame(r: MediaMetadataRetriever, dst: Pair<Int, Int>): Bitmap? =
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dst.first, dst.second)
        } else {
            null
        }

    /** 0.1s 那帧取不到（视频比 0.1s 还短）时退回第一帧。 */
    private fun MediaMetadataRetriever.frameAtTimeFallback(): Bitmap? = getFrameAtTime(0)

    private fun <T> retrieve(
        context: Context,
        uri: Uri,
        log: MediaPickerLog,
        block: (MediaMetadataRetriever) -> T?,
    ): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            block(r)
        } catch (e: Exception) {
            // 损坏的容器、DRM 视频、厂商 ROM 的编解码器缺失都会走到这里
            log.w("video_probe_failed", "err" to e.javaClass.simpleName)
            null
        } catch (e: OutOfMemoryError) {
            log.w("video_probe_oom")
            null
        } finally {
            try { r.release() } catch (_: Exception) { /* release 本身也会抛，忽略 */ }
        }
    }
}

