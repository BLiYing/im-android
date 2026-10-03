package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.protocol.ErrorData

// 媒体发送的入口封装 + 重发，从 MessageService 搬出来只为控体量（那边贴 600 行硬闸）。
// 写成扩展函数：只用 MessageService 的 `internal` 成员（media / repo / ownerProvider / transmit），调用点只多 import。

/** 见 [MediaSendPipeline.createPendingRow]。 */
suspend fun MessageService.createMediaPending(
    convId: String,
    to: String,
    contentType: String,
    localPreviewUri: String,
    fileName: String,
    fileSize: Long,
    caption: String? = null,
    groupId: String? = null,
    duration: Int? = null,
    waveform: String? = null,
    mentionSpans: String? = null,
    mentions: String? = null,
): String? = media.createPendingRow(
    convId, to, contentType, localPreviewUri, fileName, fileSize, caption, groupId,
    duration, waveform, mentionSpans, mentions,
)

/** 见 [MediaSendPipeline.attachThumb]。 */
suspend fun MessageService.attachMediaThumb(clientMsgId: String, thumb: String?) =
    media.attachThumb(clientMsgId, thumb)

/** 见 [MediaSendPipeline.markFailed]。 */
suspend fun MessageService.markMediaFailed(clientMsgId: String, code: Int = 0, message: String = Str.s(R.string.net_error_file_read_failed)) =
    media.markFailed(clientMsgId, code, message)

/** 见 [MediaSendPipeline.sendBytes]。 */
suspend fun MessageService.sendMedia(
    convId: String,
    to: String,
    bytes: ByteArray,
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
    thumb: String? = null,
    waveform: String? = null,
    pendingId: String? = null,
) = media.sendBytes(
    convId, to, bytes, fileName, mimeType, contentType, caption, localPreviewUri,
    groupId, mediaW, mediaH, duration, poster, thumb, waveform, pendingId,
)

/** 见 [MediaSendPipeline.sendStream]。 */
suspend fun MessageService.sendMediaStream(
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
    thumb: String? = null,
    pendingId: String? = null,
) = media.sendStream(
    convId, to, openStream, totalBytes, fileName, mimeType, contentType, caption,
    localPreviewUri, groupId, mediaW, mediaH, duration, poster, thumb, pendingId,
)

/**
 * 重发（红❗点击 / 重连后补发）。**沿用同一个 client_msg_id**，服务端幂等去重。
 *
 * 按 clientMsgId **不按状态**查（[MessageRepository.pendingByClientId]）——红❗点击要找的
 * 正是 Failed 行，[MessageRepository.inFlight] 只挑 Sending 的会找不到它，点了跟没点一样。
 */
suspend fun MessageService.resend(clientMsgId: String) {
    val owner = ownerProvider() ?: return
    val p = repo.pendingByClientId(owner, clientMsgId) ?: return
    if (isLocalUri(p.content)) {
        // 正文仍是本地 uri：上传没走完就失败的残留。语音落在应用私有目录，进程重启后
        // 依然读得到，能重新上传；图片/视频的本地 uri 是系统相册 content://，读权限随
        // 发起进程一起没了，**绝不能原样 transmit**——那会把 content:// 当正文发给对端，
        // 存进服务端后再也改不回来（同 MessageSync.resendInFlight 里 stale 分支的注释）。
        if (p.contentType == ContentType.VOICE) {
            media.reuploadVoice(p)
        } else if (media.isUploading(clientMsgId)) {
            return // 已在传（红❗点得太快 / 重连补发撞车）：别开第二条
        } else if (media.retryUpload(p)) {
            // 正文是本应用的私有副本（≥8MB 的视频/文件）：从服务端 offset 续传，不是从头来
        } else {
            repo.onSendRejected(
                owner,
                ErrorData(
                    code = ErrCode.PARAM_INVALID,
                    message = Str.s(R.string.chat_resend_upload_incomplete),
                    clientMsgId = clientMsgId,
                ),
            )
        }
        return
    }
    transmit(
        p.clientMsgId, p.convId, p.to, p.contentType, p.content, p.replyToConvSeq,
        p.fileName, p.fileSize, p.caption, p.forwardFrom, p.groupId,
        p.mediaW, p.mediaH, p.duration, p.poster, p.thumb, p.waveform,
        // @提及三件套：mentions 取落库的 uid 列表，mentionAll 按片段**重新推导**（不另存）：
        // 片段里已经含了每个 token 指向谁，空 uid 就是 @所有人——两份状态早晚会不一致
        mentions = Mention.parseMentions(p.mentions),
        mentionAll = Mention.mentionAllForResend(p.forwardFrom, p.mentionSpans), // 转发行不重放 @所有人（与首发 forward() 一致）
        mentionSpans = Mention.parseSpans(p.mentionSpans),
    )
}
