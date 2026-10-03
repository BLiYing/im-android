package com.libeyond.imandroid.data

/** 暂停 ⇄ 继续一条分片上传中的媒体（不可暂停 / 没在传返回 false）。 */
fun MessageService.toggleUploadPause(clientMsgId: String): Boolean = media.togglePause(clientMsgId)

/**
 * 取消发送 / 删除失败行：停上传任务、删私有副本与续传旁路文件、清进度、删待发行。
 * 对文本等无副本的待发行同样适用（只删行）。
 */
suspend fun MessageService.cancelPending(clientMsgId: String) = media.cancel(clientMsgId)
