package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.UploadApi
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        waveform: String?,
        mentions: List<String>, mentionAll: Boolean, mentionSpans: List<com.libeyond.imandroid.sdk.protocol.MentionSpan>,
    ) -> Unit,
    private val log: IMLog.Tagged,
    /** 应用级作用域：上传在这里跑，**离开聊天页不取消**（对齐 iOS 常驻的 `IMMediaSendService`）。 */
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** 待发媒体的私有副本（续传 / 取消清理）。 */
    internal val store: PendingMediaStore,
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
    fun isUploading(clientMsgId: String): Boolean = uploading.contains(clientMsgId) || jobs.containsKey(clientMsgId)

    /** 一次只传一条（对齐 iOS 的串行媒体队列）：同批多个大文件并发会互相抢带宽、谁都传不完。 */
    private val serial = kotlinx.coroutines.sync.Mutex()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val uploaders = java.util.concurrent.ConcurrentHashMap<String, ChunkedUploader>()

    /** 被用户取消、但一次性整包上传还在途的：回来时丢弃结果（整包 POST 无法中途掐断，iOS 同）。 */
    private val cancelled = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * 开一条**私有副本的分片上传**（应用作用域，常驻；暂停 / 续传 / 取消见 [ChunkedUploader]）。
     * 副本里已有 `upload_id` 旁路文件时从服务端 offset 续传（冷启动补发、点重试都走这里）。
     */
    fun startFileUpload(
        cid: String, convId: String, to: String, file: java.io.File, fileName: String, mime: String,
        contentType: String, caption: String?, groupId: String?,
        mediaW: Int?, mediaH: Int?, duration: Int?, poster: String?, thumb: String?,
    ) {
        // 汇聚点互斥：补发 / 点重试 / 冷启动补发可能同时到，只许一个赢（putIfAbsent + 懒启动，赢家才 start）
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                serial.withLock {
                    runFileUpload(cid, convId, to, file, fileName, mime, contentType, caption, groupId, mediaW, mediaH, duration, poster, thumb)
                }
            } finally {
                jobs.remove(cid); uploaders.remove(cid); uploadProgress.clear(cid)
            }
        }
        uploadProgress.queued(cid, file.length())
        if (jobs.putIfAbsent(cid, job) != null) { job.cancel(); return } // 输家：已有同一条在跑，它的进度态别动
        job.start()
    }

    private suspend fun runFileUpload(
        cid: String, convId: String, to: String, file: java.io.File, fileName: String, mime: String,
        contentType: String, caption: String?, groupId: String?,
        mediaW: Int?, mediaH: Int?, duration: Int?, poster: String?, thumb: String?,
    ) {
        val owner = ownerProvider() ?: return
        // 入队到开跑之间用户可能点了 ✕（行已删）：行不在了就别传、别发，顺手清副本
        if (repo.pendingByClientId(owner, cid) == null) { store.remove(file); return }
        repo.markPendingSending(owner, cid) // 重试/补发时它可能是 Failed
        val total = file.length()
        uploadProgress.uploading(cid, 0, total, pausable = true)
        val up = ChunkedUploader(
            transport = upload, file = file, fileName = fileName, mime = mime,
            initialUploadId = store.uploadIdOf(file),
            onUploadId = { store.setUploadId(file, it) },
            onProgress = { s, t -> uploadProgress.report(cid, s, t) },
        )
        uploaders[cid] = up
        val watch = scope.launch { up.paused.collect { uploadProgress.setPaused(cid, it) } }
        val r = try {
            up.run()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 用户取消 / 退出登录：清理由 cancel() 或 finally 做，绝不标失败
        } catch (e: ChunkedUploader.LocalReadFailed) {
            markFailed(cid, message = Str.s(R.string.chat_media_local_file_lost))
            log.w("media_local_copy_lost", "cid" to cid)
            return
        } catch (e: com.libeyond.imandroid.sdk.http.ApiException) {
            // 失败保留副本与 upload_id：点红❗重试会从服务端 offset 续传，不是从头来
            // 传输层错误的 message 是底层原文（可能是英文 / 内部描述），不上屏：换成本地化的统一文案
            val shown = if (e.isTransport) Str.s(R.string.chat_media_file_upload_failed) else e.message
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(code = e.code, message = shown, clientMsgId = cid),
            )
            log.w("media_file_upload_failed", "cid" to cid, "code" to e.code)
            return
        } finally {
            watch.cancel()
        }
        // 先把行换成服务端地址、再删副本：反过来的话中间被杀，行还指着已删的 file://，补发会报「本地文件已丢失」而服务端其实已收齐
        repo.updatePendingContent(owner, cid, r.url, r.size)
        store.remove(file)
        val at = repo.pendingByClientId(owner, cid)
        transmit(
            cid, convId, to, contentType, r.url, null,
            fileName, r.size, caption, null, groupId,
            mediaW, mediaH, duration, poster, thumb, null,
            Mention.parseMentions(at?.mentions),
            Mention.mentionAllFromSpans(at?.mentionSpans),
            Mention.parseSpans(at?.mentionSpans),
        )
    }

    /** 暂停 ⇄ 继续。返回是否有可暂停的任务（整包上传 / 已排队未开始返回 false，点了不处理）。 */
    fun togglePause(cid: String): Boolean {
        val u = uploaders[cid] ?: return false
        if (u.paused.value) u.resume() else u.pause()
        return true
    }

    /**
     * 取消发送 / 删除失败行：停任务、删私有副本与旁路文件、清进度、删待发行（一步到位）。
     * 一次性整包上传无法中途掐断，记进 [cancelled]，回来时丢弃结果。
     */
    suspend fun cancel(cid: String) {
        val owner = ownerProvider() ?: return
        val chunked = jobs.remove(cid)
        chunked?.cancel()
        // 一次性整包路径取消不了在途 POST；还没开传（排队中）的也要记，开传时据此直接丢弃
        if (chunked == null && (uploading.contains(cid) || uploadProgress.states.value.containsKey(cid))) cancelled += cid
        uploaders.remove(cid)
        repo.pendingByClientId(owner, cid)?.let { p -> store.fileOf(p.content)?.let(store::remove) }
        uploadProgress.clear(cid)
        // 帧可能已经发出去、ack 还在路上：登记一下，ack 回来时别落一条没正文的骨架气泡（消息本身会经 sync 补齐）
        cancelledSends += cid
        repo.pending.remove(owner, cid)
        log.i("media_send_cancelled", "cid" to cid)
    }

    /**
     * 点重试 / 冷启动补发：正文是**本仓私有副本**的待发行 → 重新开分片上传（从服务端 offset 续）。
     * 副本不在了就如实说「本地文件已丢失」。返回 false = 这条不是私有副本（调用方走别的分支）。
     */
    suspend fun retryUpload(p: PendingMessageEntity): Boolean {
        val f = store.fileOf(p.content) ?: return false
        val owner = ownerProvider() ?: return true
        if (!f.exists() || f.length() <= 0L) {
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(code = 0, message = Str.s(R.string.chat_media_local_file_lost), clientMsgId = p.clientMsgId),
            )
            return true
        }
        startFileUpload(
            p.clientMsgId, p.convId, p.to, f, p.fileName ?: f.name, mimeOfName(p.fileName ?: f.name), p.contentType,
            p.caption, p.groupId, p.mediaW, p.mediaH, p.duration, p.poster, p.thumb,
        )
        return true
    }

    private fun mimeOfName(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

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
        /**
         * 时长 / 波形指纹（仅 voice）：与图片/视频不同，语音**录完当场就知道**，
         * 不用等异步解码，落行时就能一并写上，气泡首次渲染就有真实时长与波形。
         */
        duration: Int? = null,
        waveform: String? = null,
        /** 配文 @（仅 caption 路径）：已编码的 JSON，随待发行落库，重发据此重新推导。 */
        mentionSpans: String? = null,
        mentions: String? = null,
    ): String? {
        val owner = ownerProvider() ?: return null
        return repo.createPending(
            owner = owner, convId = convId, to = to,
            content = localPreviewUri, contentType = contentType,
            groupId = groupId, fileName = fileName, fileSize = fileSize, caption = caption,
            duration = duration, waveform = waveform, mentionSpans = mentionSpans, mentions = mentions,
        ).clientMsgId.also { if (contentType != ContentType.VOICE) uploadProgress.queued(it, fileSize) } // 整批行先落库=先排队，气泡显「等待中」+ ✕
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
    suspend fun markFailed(clientMsgId: String, code: Int = 0, message: String = Str.s(R.string.net_error_file_read_failed)) {
        val owner = ownerProvider() ?: return
        uploadProgress.clear(clientMsgId) // 失败行不再是「排队/传输中」，别留着让 cancel 把它记进 cancelled
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
        /** 语音振幅指纹（仅 voice）。 */
        waveform: String? = null,
        /** 已由 [createPendingRow] 落好的行；传 null 则在这里现落一条。 */
        pendingId: String? = null,
    ) {
        val owner = ownerProvider() ?: return
        if (pendingId != null && cancelled.remove(pendingId)) return // 排队时就被用户取消了：不传、不发
        val cid = pendingId?.also {
            // 行是发送前就落的，元数据此刻才算出来——**必须回写**，否则 resend 丢字段
            repo.updatePendingMedia(owner, it, mediaW, mediaH, duration, poster, thumb, waveform)
            repo.updatePendingContent(owner, it, localPreviewUri, bytes.size.toLong())
        } ?: repo.createPending(
            owner = owner, convId = convId, to = to,
            content = localPreviewUri, contentType = contentType,
            groupId = groupId,
            fileName = fileName, fileSize = bytes.size.toLong(), caption = caption,
            mediaW = mediaW, mediaH = mediaH, duration = duration, poster = poster, thumb = thumb,
            waveform = waveform,
        ).clientMsgId
        uploading += cid
        // 整包上传：不可暂停、无字节进度（一次性 POST），只让气泡知道「在传」
        uploadProgress.uploading(cid, 0, bytes.size.toLong(), pausable = false)
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
            uploadProgress.clear(cid)
        }
        // 传的途中被用户取消了（整包 POST 掐不断，只能回来丢弃）：行已删，别再发帧
        if (cancelled.remove(cid)) return
        repo.updatePendingContent(owner, cid, r.url, r.size)
        // **自己发的那份字节直接进缓存**：不然发完自己看自己的文件是「未下载 ↓」，
        // 还要再从服务端下回来一遍（对齐 iOS 的 adopt）。
        // 只有整包这条路能这么做——分片那条（视频/大文件）字节从没同时在内存里，
        // 为了 adopt 去复制一份几百 MB 的副本不划算，那种自己发的大件仍会显 ↓。
        cache.adopt(r.url, bytes, isVideo = contentType == ContentType.VIDEO)
        val at = repo.pendingByClientId(owner, cid) // 配文 @ 随待发行落库，这里读出来带上
        transmit(
            cid, convId, to, contentType, r.url, null,
            fileName, r.size, caption, null, groupId,
            mediaW, mediaH, duration, poster, thumb, waveform,
            Mention.parseMentions(at?.mentions),
            Mention.mentionAllFromSpans(at?.mentionSpans),
            Mention.parseSpans(at?.mentionSpans),
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
        // 对齐 iOS：≥ 分片阈值才走「私有副本 + 可暂停续传」；更小的一次性直传（不可暂停，失败重试从头）
        if (totalBytes >= ChunkedUploader.DEFAULT_CHUNK) {
            val f = store.newFile(cid, fileName)
            // 复制可能几秒到几分钟：这段也算「在传」（重连补发据此跳过，不误标红❗），并且用户随时可能点 ✕
            uploadProgress.queued(cid, totalBytes) // 复制期就有「准备中…」+ ✕（createPendingRow 之外落的行此前没有）
            uploading += cid
            val copied = try { store.copyFrom(openStream, f) } finally { uploading -= cid }
            if (cancelled.remove(cid)) { store.remove(f); return } // 复制期间被取消：行已删，副本也扔
            if (!copied) {
                markFailed(cid, message = Str.s(R.string.chat_media_stage_failed))
                return
            }
            repo.updatePendingContent(owner, cid, store.refOf(f), f.length())
            startFileUpload(
                cid, convId, to, f, fileName, mimeType, contentType, caption, groupId,
                mediaW, mediaH, duration, poster, thumb,
            )
            return
        }
        val small = try {
            withContext(Dispatchers.IO) { openStream()?.use { it.readBytes() } }
        } catch (e: java.io.IOException) {
            null
        } catch (e: SecurityException) {
            null // content uri 的读权限被撤
        }
        if (small == null) {
            markFailed(cid, message = Str.s(R.string.net_error_file_read_failed))
            return
        }
        sendBytes(
            convId, to, small, fileName, mimeType, contentType, caption, localPreviewUri,
            groupId, mediaW, mediaH, duration, poster, thumb, null, cid,
        )
    }

    /**
     * 语音重传（[MessageService.resend] 遇到失败且正文仍是本地 `file://` 路径时走这条）。
     *
     * 与图片/视频**刻意不同**：图片/视频的本地 uri 是系统相册的 `content://`，
     * 读权限随发起进程一起没了，杀进程/断线后读不回来，只能标失败让用户重选
     * （见 [MessageSync.resendInFlight] 的注释）。语音的本地文件落在**应用私有目录**
     * （[VoiceRecorder.pendingDir]），是自己的字节，进程重启后依然读得到——
     * 没有理由让用户重新说一遍话，直接重新上传即可（iOS `IMVoiceRecorder` 同一取舍）。
     */
    suspend fun reuploadVoice(p: PendingMessageEntity) {
        val owner = ownerProvider() ?: return
        val file = File(p.content.removePrefix("file://"))
        if (!file.exists() || file.length() <= 0) {
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(
                    code = 0, message = Str.s(R.string.chat_voice_original_lost), clientMsgId = p.clientMsgId,
                ),
            )
            log.w("voice_reupload_missing", "cid" to p.clientMsgId)
            return
        }
        repo.markPendingSending(owner, p.clientMsgId)
        val bytes = try {
            withContext(Dispatchers.IO) { file.readBytes() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 离开会话 / 退出登录取消了这次重传：状态留在「发送中」等下次机会，
            // 不能吞掉——吞了协程取消就失效了（CODING_STYLE §5）。
            throw e
        } catch (e: Exception) {
            repo.onSendRejected(
                owner,
                com.libeyond.imandroid.sdk.protocol.ErrorData(
                    code = 0, message = Str.s(R.string.chat_voice_file_read_failed), clientMsgId = p.clientMsgId,
                ),
            )
            log.w("voice_reupload_read_failed", "cid" to p.clientMsgId, "err" to e.javaClass.simpleName)
            return
        }
        sendBytes(
            convId = p.convId, to = p.to, bytes = bytes,
            fileName = p.fileName ?: "voice.m4a", mimeType = "audio/mp4",
            contentType = ContentType.VOICE, caption = p.caption,
            localPreviewUri = p.content, groupId = p.groupId,
            duration = p.duration, waveform = p.waveform,
            pendingId = p.clientMsgId,
        )
    }
}
