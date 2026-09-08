package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.UploadApi
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 一条媒体消息从「选中」到「发出去」的完整链路：
 * **落待发行 → 上传 → 把本地 uri 换成服务端 url → 发帧**。
 *
 * 从 [MessageService] 拆出来（2026-09-08，那份文件到 626/600 行）。拆的**边界**不是
 * 按行数随便切的：图片（整包）与视频（分片）是两条上传路径，但**其余状态机必须逐字一致**
 * ——先落待发、失败标红、成功换 URL 再发帧。两条路分叉过一次就会出现
 * 「视频发失败了但没有红❗」这种查不出来的事，所以它们要待在同一个文件里互相盯着。
 *
 * @param transmit 真正发帧的动作（socket 层在 [MessageService] 里，本类不碰 socket）。
 */
internal class MediaSendPipeline(
    private val repo: MessageRepository,
    private val cache: MediaCache,
    private val upload: UploadApi,
    private val uploadProgress: UploadProgress,
    private val ownerProvider: () -> String?,
    private val transmit: (
        clientMsgId: String, convId: String, to: String, contentType: String,
        content: String, replyToConvSeq: Long?, fileName: String?, fileSize: Long?,
        caption: String?, forwardFrom: String?, groupId: String?,
        mediaW: Int?, mediaH: Int?, duration: Int?, poster: String?, thumb: String?,
    ) -> Unit,
    private val log: IMLog.Tagged,
) {

    /**
     * **正在上传中**的 client_msg_id。
     *
     * 存在的理由：待发行现在是在压缩/上传**之前**就整批落库的（宫格要立刻成形），
     * 于是「行在库里、正文还是本地 uri、但上传其实正在进行」这个窗口被拉长了——
     * 一批带大视频的能有好几分钟。而 [MessageService.resendInFlight] 在重连时会把
     * 「正文仍是本地 uri」的待发行一律判为"上传没走完的残留"标失败。**那对还在传的
     * 是误判**：用户会看到一条红❗，而它的上传其实还在跑，几秒后又自己发出去了。
     * 这里记一笔，让重连补发跳过它们。
     */
    private val uploading = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** 这条是不是正在上传（重连补发据此跳过，既不重发也不标失败）。 */
    fun isUploading(clientMsgId: String): Boolean = uploading.contains(clientMsgId)

    /**
     * **只落一条待发行**，不压缩、不上传、不发帧；返回 `client_msg_id`。
     *
     * 为什么要把这一步单拎出来：一批媒体此前是「逐个：压缩 → 落待发行 → 上传 → 发帧」，
     * 于是屏幕上的待发气泡是**一张一张冒出来的**（压一张要几百毫秒到几秒），
     * 而宫格要 ≥2 条同组待发行才成形——用户点完「发送」先看到空白，再看到单张，
     * 最后才凑成宫格。**先把整批行一次性落库**，宫格在点发送那一刻就在。
     *
     * 宽高/时长/封面此刻还不知道（要解码），由 [MessageRepository.updatePendingMedia]
     * 在算出来之后回写——不回写就是 resend 丢字段。
     */
    suspend fun createPendingRow(
        convId: String,
        to: String,
        contentType: String,
        localPreviewUri: String,
        fileName: String,
        fileSize: Long,
        caption: String? = null,
        groupId: String? = null,
    ): String? {
        val owner = ownerProvider() ?: return null
        return repo.createPending(
            owner = owner, convId = convId, to = to,
            content = localPreviewUri, contentType = contentType,
            groupId = groupId, fileName = fileName, fileSize = fileSize, caption = caption,
        ).clientMsgId
    }

    /**
     * 回写极小缩略（[com.libeyond.imandroid.data.TinyThumb]）。
     *
     * 与宽高/时长同理：待发行是压缩前就落的，thumb 要解码才算得出来。
     * **落进待发行**而不是只当发帧参数——ack 不回带它，不落的话自己发的图在
     * 自己这一侧没有磨砂占位，对端却有。
     */
    suspend fun attachThumb(clientMsgId: String, thumb: String?) {
        if (thumb == null) return
        val owner = ownerProvider() ?: return
        repo.updatePendingMedia(owner, clientMsgId, thumb = thumb)
    }

    /**
     * 把一条已落库的待发行标成失败（红❗可重试）。
     *
     * 用在「行已经落在屏幕上了，但还没走到上传就出错」那一段——读不出字节、
     * 视频大小为 0 之类。**不标的话那一格会永远转圈**：既没上传也没失败，
     * 用户只能杀进程。
     */
    suspend fun markFailed(clientMsgId: String, code: Int = 0, message: String = "读取失败") {
        val owner = ownerProvider() ?: return
        repo.onSendRejected(
            owner,
            com.libeyond.imandroid.sdk.protocol.ErrorData(
                code = code, message = message, clientMsgId = clientMsgId,
            ),
        )
    }

    suspend fun sendBytes(
        convId: String,
        to: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        contentType: String,
        caption: String? = null,
        localPreviewUri: String = "",
        /** 相册分组：同批 ≥2 张时由调用方生成一个共享 ID，1 张传 null。 */
        groupId: String? = null,
        /** 像素宽高 / 视频时长 / 视频封面（§4.1）；拿不到传 null，**不要传负数**（服务端拒发）。 */
        mediaW: Int? = null,
        mediaH: Int? = null,
        duration: Int? = null,
        poster: String? = null,
        /** 极小模糊缩略（[com.libeyond.imandroid.data.TinyThumb]），随消息带给收端做占位。 */
        thumb: String? = null,
        /** 已由 [createPendingRow] 落好的行；传 null 则在这里现落一条。 */
        pendingId: String? = null,
    ) {
        val owner = ownerProvider() ?: return
        val cid = pendingId?.also {
            // 行是发送前就落的，元数据此刻才算出来——**必须回写**，否则 resend 丢字段
            repo.updatePendingMedia(owner, it, mediaW, mediaH, duration, poster, thumb)
            repo.updatePendingContent(owner, it, localPreviewUri, bytes.size.toLong())
        } ?: repo.createPending(
            owner = owner, convId = convId, to = to,
            content = localPreviewUri, contentType = contentType,
            groupId = groupId,
            fileName = fileName, fileSize = bytes.size.toLong(), caption = caption,
            mediaW = mediaW, mediaH = mediaH, duration = duration, poster = poster, thumb = thumb,
        ).clientMsgId
        uploading += cid
        val r = try {
            upload.upload(bytes, fileName, mimeType, asVoice = contentType == ContentType.VOICE)
        } catch (e: com.libeyond.imandroid.sdk.http.ApiException) {
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(
                    code = e.code, message = e.message, clientMsgId = cid,
                ),
            )
            log.w("media_upload_failed", "cid" to cid, "code" to e.code)
            return
        } finally {
            uploading -= cid
        }
        repo.updatePendingContent(owner, cid, r.url, r.size)
        // **自己发的那份字节直接进缓存**：不然发完自己看自己的文件是「未下载 ↓」，
        // 还要再从服务端下回来一遍（对齐 iOS 的 adopt）。
        // 只有整包这条路能这么做——分片那条（视频/大文件）字节从没同时在内存里，
        // 为了 adopt 去复制一份几百 MB 的副本不划算，那种自己发的大件仍会显 ↓。
        cache.adopt(r.url, bytes, isVideo = contentType == ContentType.VIDEO)
        transmit(
            cid, convId, to, contentType, r.url, null,
            fileName, r.size, caption, null, groupId,
            mediaW, mediaH, duration, poster, thumb,
        )
    }

    /**
     * 发一条**流式上传**的媒体消息（视频）。
     *
     * 与 [sendBytes] 的唯一区别是上传走分片（`UploadApi.uploadStream`）而不是整包字节：
     * 服务端视频上限 2GB，整包读进 `ByteArray` 就是当场 OOM。
     * 其余（先落待发 → 失败标红 → 成功换 URL 再发帧）**必须保持一致**——
     * 两条发送路径的状态机分叉过一次就会出现「视频发失败了但没有红❗」这种查不出来的事。
     *
     * @param openStream 每次返回从头开始的新流
     * @param totalBytes 必须准确：分片协议按声明大小校验，多一字节服务端直接判超限
     */
    suspend fun sendStream(
        convId: String,
        to: String,
        openStream: () -> java.io.InputStream?,
        totalBytes: Long,
        fileName: String,
        mimeType: String,
        contentType: String,
        caption: String? = null,
        localPreviewUri: String = "",
        groupId: String? = null,
        mediaW: Int? = null,
        mediaH: Int? = null,
        duration: Int? = null,
        poster: String? = null,
        /** 极小模糊缩略（[com.libeyond.imandroid.data.TinyThumb]），随消息带给收端做占位。 */
        thumb: String? = null,
        /** 已由 [createPendingRow] 落好的行；传 null 则在这里现落一条。 */
        pendingId: String? = null,
    ) {
        val owner = ownerProvider() ?: return
        val cid = pendingId?.also {
            repo.updatePendingMedia(owner, it, mediaW, mediaH, duration, poster, thumb)
        } ?: repo.createPending(
            owner = owner, convId = convId, to = to,
            content = localPreviewUri, contentType = contentType,
            groupId = groupId,
            // 大小在上传前就知道（分片协议要求先声明），先落库好让待发气泡显示得出来
            fileName = fileName, fileSize = totalBytes, caption = caption,
            mediaW = mediaW, mediaH = mediaH, duration = duration, poster = poster, thumb = thumb,
        ).clientMsgId
        // **开传就先置 0%**：第一片是 8MB，传完才有第一次回调。不置的话这段空窗里
        // 气泡显示的是播放钮，看着像已经发好了——真机实测一段 404MB 的视频，
        // 这个空窗有好几秒。0% 至少说明「在传」。
        uploadProgress.report(cid, 0, totalBytes)
        uploading += cid
        val r = try {
            upload.uploadStream(openStream, fileName, mimeType, totalBytes) { sent, total ->
                uploadProgress.report(cid, sent, total)
            }
        } catch (e: com.libeyond.imandroid.sdk.http.ApiException) {
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(
                    code = e.code, message = e.message, clientMsgId = cid,
                ),
            )
            log.w("media_stream_upload_failed", "cid" to cid, "code" to e.code)
            return
        } finally {
            // 成功 / 失败 / 协程被取消都要摘掉。写在 happy path 上就会留下
            // 一条永远停在 43% 的进度环——比没有进度条更让人以为程序卡死了。
            uploadProgress.clear(cid)
            uploading -= cid
        }
        repo.updatePendingContent(owner, cid, r.url, r.size)
        transmit(
            cid, convId, to, contentType, r.url, null,
            fileName, r.size, caption, null, groupId,
            mediaW, mediaH, duration, poster, thumb,
        )
    }
}
