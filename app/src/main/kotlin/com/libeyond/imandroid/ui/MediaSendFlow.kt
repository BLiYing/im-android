package com.libeyond.imandroid.ui

import android.content.Context
import android.net.Uri
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.mediapicker.MediaCompressor
import com.libeyond.mediapicker.PickedMedia
import com.libeyond.mediapicker.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 把选择器选中的一批媒体发出去。
 *
 * **两条选图路径（自建宫格页 / 权限被拒时降级的系统选择器）共用这一个出口**——
 * 不共用的话，「≥2 个才带 group_id」这条聚簇判据就会有两份，迟早分叉成
 * 「自建页发的成宫格、降级路径发的散成单张」。
 *
 * 图片走整包上传（压缩后几百 KB），**视频走分片**（服务端上限 2GB，整包读进内存就是 OOM）。
 */
internal class MediaSendFlow(
    private val context: Context,
    private val client: IMClient,
    private val conv: ConversationEntity,
) {
    private val log = IMLog.tag("IM.Media")
    private val to: String get() = if (conv.isGroup) conv.convId else conv.peerUid

    suspend fun send(items: List<PickedMedia>, sendOriginal: Boolean, onToast: (String) -> Unit) {
        if (items.isEmpty()) return
        // ≥2 个共享一个 group_id → 两端聚簇成宫格；1 个不带（普通媒体气泡）。
        // 前缀 `alb-` 与 iOS 一致，便于日志里一眼认出。
        val gid = if (items.size > 1) "alb-" + UUID.randomUUID() else null
        for (m in items) {
            val uri = Uri.parse(m.uri)
            if (m.isVideo) sendVideo(m, uri, gid, onToast) else sendImage(m, uri, gid, sendOriginal)
        }
    }

    private suspend fun sendImage(m: PickedMedia, uri: Uri, gid: String?, sendOriginal: Boolean) {
        // 压缩失败**回落原图**而不是放弃这一张——压不动的多半是奇怪格式，原样发出去反而能用
        val compressed = if (sendOriginal) {
            null
        } else {
            withContext(Dispatchers.IO) { MediaCompressor.compressImage(context, uri, log = PickerLog) }
        }
        val bytes = compressed
            ?: withContext(Dispatchers.IO) { readAllBytes(uri) }
            ?: run {
                log.w("pick_read_failed")
                return
            }
        val (w, h) = MediaCompressor.imageSizeOf(bytes) ?: (0 to 0)
        client.messages.sendMedia(
            convId = conv.convId,
            to = to,
            bytes = bytes,
            // 压过的一律改成 .jpg / image/jpeg：字节已经是 JPEG 了，
            // 名字还写 .heic 会让对端按 heic 解、必然失败
            fileName = if (compressed != null) MediaCompressor.jpegNameFor(m.displayName) else m.displayName,
            mimeType = if (compressed != null) "image/jpeg" else m.mime,
            contentType = ContentType.IMAGE,
            localPreviewUri = m.uri,
            groupId = gid,
            mediaW = w.takeIf { it > 0 },
            mediaH = h.takeIf { it > 0 },
        )
    }

    private suspend fun sendVideo(m: PickedMedia, uri: Uri, gid: String?, onToast: (String) -> Unit) {
        if (m.sizeBytes <= 0) {
            // 分片协议按声明大小校验，0 会被挡下——先给用户一句话，别静默什么都不发生
            onToast("这个视频读不出来")
            return
        }
        val info = withContext(Dispatchers.IO) { VideoProbe.info(context, uri, PickerLog) }
        // 封面：抽首帧 → 单独上传 → URL 放 poster。
        // **失败不阻断发送**——没封面的视频对端仍能点开，整条发不出去就是彻底没了。
        val posterUrl = withContext(Dispatchers.IO) { VideoProbe.poster(context, uri, log = PickerLog) }
            ?.let { bytes ->
                runCatchingCancellable {
                    client.upload.upload(bytes, "poster.jpg", "image/jpeg").url
                }.getOrNull()
            }
        client.messages.sendMediaStream(
            convId = conv.convId,
            to = to,
            openStream = { context.contentResolver.openInputStream(uri) },
            totalBytes = m.sizeBytes,
            fileName = m.displayName,
            mimeType = m.mime,
            contentType = ContentType.VIDEO,
            localPreviewUri = m.uri,
            groupId = gid,
            // 服务端对负数**直接拒发 100001**，所以拿不到就传 null，绝不传 -1
            mediaW = info?.width?.takeIf { it > 0 },
            mediaH = info?.height?.takeIf { it > 0 },
            duration = info?.durationMs?.takeIf { it > 0 },
            poster = posterUrl,
        )
    }

    /**
     * 读原始字节（「原图」模式，或压缩失败的回落路径）。
     *
     * **这里不再有 20MB 闸门**：闸门挪到了选择器里（`MediaPick.selectable`，按服务端真实上限判），
     * 在宫格上就把超限的置灰，比让用户选完再失败好。
     * 仍可能 OOM——原图模式下一张 100MB 的图确实会整个进内存；压缩路径（默认）不走这里。
     */
    private fun readAllBytes(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        log.w("pick_read_error", "err" to e.javaClass.simpleName)
        null
    } catch (e: OutOfMemoryError) {
        // 大文件 OOM 是 Error，catch(Exception) 抓不到
        log.w("pick_read_oom")
        null
    }
}
