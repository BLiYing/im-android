package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.sendCard
import android.content.Context
import android.net.Uri
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.ThumbEncode
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.Mention
import com.libeyond.imandroid.i18n.Str
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

    /**
     * [caption] / [at]：**仅单件**（iOS 同：粘贴单图 + 输入栏文字合并成一条 caption 消息；多件/相册不带）。
     * 配文 @ 在发送那一刻已按文本复核好（[MentionComposer.resolve]），随待发行落库。
     */
    suspend fun send(
        items: List<PickedMedia>, sendOriginal: Boolean,
        caption: String? = null, at: MentionComposer.Resolved? = null,
        onToast: (String) -> Unit,
    ) {
        if (items.isEmpty()) return
        // ≥2 个共享一个 group_id → 两端聚簇成宫格；1 个不带（普通媒体气泡）。
        // 前缀 `alb-` 与 iOS 一致，便于日志里一眼认出。
        val gid = if (items.size > 1) "alb-" + UUID.randomUUID() else null

        // ① **先把整批待发行一次性落库**，再去压缩/上传。
        //    此前是「逐个：压缩 → 落行 → 上传 → 发帧」，压一张要几百毫秒到几秒，
        //    于是待发气泡一张一张往外冒；而宫格要 ≥2 条同组待发行才成形，
        //    用户点完发送先看到空白、再看到单张，最后才凑成宫格
        //    （2026-09-08 用户报的「发送时不是九宫格形态」的前半段）。
        //    落行只写一次本地库，几十毫秒内整组宫格就在屏幕上了。
        val cids = items.map { m ->
            client.messages.createMediaPending(
                convId = conv.convId,
                to = to,
                contentType = if (m.isVideo) ContentType.VIDEO else ContentType.IMAGE,
                // 本地 uri 直接给 Coil 加载——选完立刻有图，不用等上传
                localPreviewUri = m.uri,
                fileName = m.displayName,
                fileSize = m.sizeBytes,
                groupId = gid,
                caption = caption.takeIf { items.size == 1 },
                mentionSpans = at?.let { Mention.encodeSpans(it.spans) }.takeIf { items.size == 1 },
                mentions = at?.let { Mention.encodeMentions(it.mentions) }.takeIf { items.size == 1 },
            )
        }

        // ② 再逐个压缩 / 上传 / 发帧，沿用上面那一行的 client_msg_id
        for ((idx, m) in items.withIndex()) {
            val uri = Uri.parse(m.uri)
            val cid = cids[idx]
            if (m.isVideo) sendVideo(m, uri, gid, cid, onToast) else sendImage(m, uri, gid, cid, sendOriginal, caption.takeIf { items.size == 1 })
        }
    }

    private suspend fun sendImage(
        m: PickedMedia,
        uri: Uri,
        gid: String?,
        pendingId: String?,
        sendOriginal: Boolean,
        caption: String? = null,
    ) {
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
                // 行已经落在屏幕上了，读不出来就得把它标失败——否则那一格永远转圈
                pendingId?.let { client.messages.markMediaFailed(it) }
                return
            }
        val (w, h) = MediaCompressor.imageSizeOf(bytes) ?: (0 to 0)
        // 极小模糊缩略（M4-7）：**由真正要发出去的那份字节生成**，不是原始文件——
        // 压缩会改尺寸/朝向，拿原图算出来的占位与收端最终看到的图对不上。
        // 算完立刻回写待发行：ack 不回带 thumb，不回写的话自己这一侧没有占位。
        val thumb = withContext(Dispatchers.IO) { ThumbEncode.fromImageBytes(bytes) }
        pendingId?.let { client.messages.attachMediaThumb(it, thumb) }
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
            thumb = thumb,
            pendingId = pendingId,
            caption = caption,
        )
    }

    private suspend fun sendVideo(
        m: PickedMedia,
        uri: Uri,
        gid: String?,
        pendingId: String?,
        onToast: (String) -> Unit,
    ) {
        if (m.sizeBytes <= 0) {
            // 分片协议按声明大小校验，0 会被挡下——先给用户一句话，别静默什么都不发生
            onToast(Str.s(R.string.chat_media_video_read_failed))
            pendingId?.let { client.messages.markMediaFailed(it) }
            return
        }
        val info = withContext(Dispatchers.IO) { VideoProbe.info(context, uri, PickerLog) }
        // 封面：抽首帧 → 单独上传 → URL 放 poster。
        // **失败不阻断发送**——没封面的视频对端仍能点开，整条发不出去就是彻底没了。
        val posterBytes = withContext(Dispatchers.IO) { VideoProbe.poster(context, uri, log = PickerLog) }
        val posterUrl = posterBytes?.let { bytes ->
            runCatchingCancellable {
                client.upload.upload(bytes, "poster.jpg", "image/jpeg").url
            }.getOrNull()
        }
        // 视频的缩略取**封面首帧**（iOS 同）——视频本身解不出 20px 缩略，
        // 而封面正好是收端未下载时该看到的那一帧
        val thumb = withContext(Dispatchers.IO) { ThumbEncode.fromImageBytes(posterBytes) }
        pendingId?.let { client.messages.attachMediaThumb(it, thumb) }
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
            thumb = thumb,
            pendingId = pendingId,
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

    /** 发一个任意文件（➕ 面板「文件」）。类型白名单在服务端，端上不预筛。 */
    suspend fun sendFile(uri: Uri, onToast: (String) -> Unit) {
        val meta = withContext(Dispatchers.IO) { describeFile(uri) }
        if (meta == null) {
            onToast(Str.s(R.string.chat_media_file_read_failed))
            return
        }
        val (name, size) = meta
        if (size <= 0) {
            onToast(Str.s(R.string.chat_media_file_read_failed))
            return
        }
        // **一律走分片**：文件上限同样是 2GB，整包读进内存不行。
        // 小文件多一次 init/complete 往返，换来的是「多大都不会 OOM」，值。
        client.messages.sendMediaStream(
            convId = conv.convId,
            to = to,
            openStream = { context.contentResolver.openInputStream(uri) },
            totalBytes = size,
            fileName = name,
            mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
            contentType = ContentType.FILE,
            localPreviewUri = uri.toString(),
        )
    }

    /**
     * 发一条刚录好的语音（[com.libeyond.imandroid.voice.VoiceRecorder.Event.Stopped]）。
     *
     * 与图片/视频**刻意不同**：本地文件落在应用私有目录（`voice_pending/`），不是系统相册的
     * `content://`——正文先存 `file://` 本地路径（[com.libeyond.imandroid.data.isLocalUri] 认得这个前缀），
     * 上传成功后 [com.libeyond.imandroid.data.MediaSendPipeline.sendBytes] 自会换成服务端 url。
     * 时长/波形录完当场就有，不用像图片宽高那样等异步解码。
     */
    suspend fun sendVoice(file: java.io.File, durationMs: Int, waveform: String?) {
        if (!file.exists() || file.length() <= 0) {
            log.w("voice_send_missing_file")
            return
        }
        val localUri = "file://" + file.path
        val pendingId = client.messages.createMediaPending(
            convId = conv.convId,
            to = to,
            contentType = ContentType.VOICE,
            localPreviewUri = localUri,
            fileName = file.name,
            fileSize = file.length(),
            duration = durationMs,
            waveform = waveform,
        )
        val bytes = try {
            withContext(Dispatchers.IO) { file.readBytes() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 页面已经退出，别把取消吞成失败态（CODING_STYLE §5）
        } catch (e: Exception) {
            // 行已经落在屏幕上了（createMediaPending 刚建的那条），读不出来就得标失败，
            // 否则那一格永远转圈——同 sendImage/sendVideo 读失败的处理（MediaSendPipeline.reuploadVoice 同款）。
            log.w("voice_send_read_failed", "err" to e.javaClass.simpleName)
            pendingId?.let { client.messages.markMediaFailed(it, message = Str.s(R.string.chat_voice_file_read_failed)) }
            return
        }
        client.messages.sendMedia(
            convId = conv.convId,
            to = to,
            bytes = bytes,
            fileName = file.name,
            mimeType = "audio/mp4",
            contentType = ContentType.VOICE,
            localPreviewUri = localUri,
            duration = durationMs,
            waveform = waveform,
            pendingId = pendingId,
        )
    }

    /** 发个人名片（➕ 面板「个人名片」）。
     *
     * **写进卡片的必须是公开名**（[DisplayName.publicNameOfFriend]）——
     * 这段 JSON 会原样发给第三个人，带备注就是把「我给他起的外号」发出去。
     * iOS 与 im-web 各为此出过一次线上事故（IMServer `docs/UI.md` 隐私红线）。
     */
    suspend fun sendContactCard(f: FriendEntry, onToast: (String) -> Unit) {
        if (f.userId.isBlank()) {
            onToast(Str.s(R.string.chat_media_contact_card_incomplete))
            return
        }
        client.messages.sendCard(
            convId = conv.convId,
            to = to,
            contentType = ContentType.CONTACT,
            json = CardContent.encodeContact(
                uid = f.userId,
                username = f.username,
                nickname = DisplayName.publicNameOfFriend(f),
                avatarUrl = f.avatarUrl,
            ),
        )
    }

    /** 名字与大小；读不到返回 null。 */
    private fun describeFile(uri: Uri): Pair<String, Long>? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                val n = (if (nameIdx >= 0) c.getString(nameIdx) else null)
                    ?: ("file_" + System.currentTimeMillis())
                val sz = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                n to sz
            } else {
                null
            }
        }
    } catch (e: Exception) {
        log.w("file_meta_failed", "err" to e.javaClass.simpleName)
        null
    }

    companion object {
        /**
         * 给系统相机一个可写的 `content://`。
         *
         * 系统相机是**另一个进程**，Android 7+ 起跨进程传 `file://` 会抛
         * `FileUriExposedException`，所以必须走 FileProvider（authority 与 manifest 里那个一致）。
         * 落在 `cache/camera/`，只开这一个子目录。
         */
        fun newCameraUri(context: Context): Uri? = try {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            // 上一张的原片在这里躺着——相机原片 3~5MB，不清就是每拍一张漏一份。
            // 在**下次拍照时**清而不是发完就删：发送是异步的，发完立刻删会和上传抢文件。
            purgeCameraCache(dir)
            val f = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
            androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", f,
            )
        } catch (e: Exception) {
            IMLog.tag("IM.Media").w("camera_uri_failed", "err" to e.javaClass.simpleName)
            null
        }

        /**
         * 清掉相机临时目录里**上一轮**的原片。
         *
         * 只删比 [CAMERA_CACHE_TTL_MS] 老的：刚拍的那张可能还在上传，删了就发不出去。
         * 用时间而不是「删除除最新一个以外的全部」——后者在连拍两张时会删掉还在传的那张。
         */
        private fun purgeCameraCache(dir: java.io.File) {
            val cutoff = System.currentTimeMillis() - CAMERA_CACHE_TTL_MS
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff && !f.delete()) {
                    IMLog.tag("IM.Media").w("camera_cache_purge_failed", "name" to f.name)
                }
            }
        }

        /** 相机原片在缓存里保留多久。10 分钟足够任何一次上传跑完（含失败重试）。 */
        private const val CAMERA_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
