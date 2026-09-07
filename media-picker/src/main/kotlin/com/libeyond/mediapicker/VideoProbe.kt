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

    /** 必须在 IO 线程调用。读不到返回 null。 */
    fun info(context: Context, uri: Uri, log: MediaPickerLog = MediaPickerLog.None): VideoInfo? =
        retrieve(context, uri, log) { r ->
            VideoInfo(
                width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0,
            ).let {
                // 竖着拍的视频，宽高在容器里是横的、靠旋转标记转过来——
                // 不换的话对端按 16:9 预留气泡，实际是 9:16，加载完整个跳一下。
                val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) it.copy(width = it.height, height = it.width) else it
            }
        }

    /** 首帧封面 JPEG 字节。必须在 IO 线程调用。读不到返回 null（调用方发无封面视频）。 */
    fun poster(
        context: Context,
        uri: Uri,
        maxEdge: Int = POSTER_MAX_EDGE,
        log: MediaPickerLog = MediaPickerLog.None,
    ): ByteArray? = retrieve(context, uri, log) { r ->
        val frame = r.getFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.frameAtTimeFallback()
            ?: return@retrieve null
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

