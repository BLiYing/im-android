package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType

/** 待发件（还没有 conv_seq）长按菜单里的一项。 */
enum class PendingAction { Copy, CancelSend, Delete }

/**
 * 待发件长按菜单的判据（纯函数，对齐 iOS `messageActionsForMessage:` 对 convSeq<=0 的那几条）：
 * - 复制：有可复制的文字；
 * - 取消发送：**本地媒体件**（图/视频/文件/语音）仍在发送或已失败——停任务 + 删副本 + 删行一步到位；
 *   文本件没有后台任务，iOS 不给；
 * - 删除：只对**失败**的给（发送中的删行不停上传，传完仍会发出去，是僵尸任务）。
 *   失败的媒体件 iOS 同时给「取消发送」与「删除」两项（效果相同，照搬）。
 *
 * 宫格里的待发格与单条待发气泡共用这一份。
 */
object PendingMenu {
    fun actions(contentType: String, failed: Boolean, hasCopyText: Boolean): List<PendingAction> = buildList {
        if (hasCopyText) add(PendingAction.Copy)
        if (isLocalMedia(contentType)) add(PendingAction.CancelSend)
        if (failed) add(PendingAction.Delete)
    }

    private fun isLocalMedia(type: String) = type == ContentType.IMAGE || type == ContentType.VIDEO ||
        type == ContentType.FILE || type == ContentType.VOICE
}
