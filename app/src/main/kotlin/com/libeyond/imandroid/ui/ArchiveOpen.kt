package com.libeyond.imandroid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 点开归档里的一项。单聊详情与群资料共用（两处行为必须一样，各写一遍必然分叉）。
 *
 * **文件不进图片查看器**：那里没有文件分支，一个 PDF 会被当成图片交给 `ZoomableImage`，
 * 屏幕上一片空白（2026-09-08 查出来的死路）。
 */
internal fun openArchiveItem(
    client: IMClient,
    context: Context,
    item: ConvMediaItem,
    onToast: (String) -> Unit,
    onViewer: (ConvMediaItem) -> Unit,
) {
    if (item.contentType == ContentType.FILE) {
        val local = client.downloads.localFile(item.content)
        if (local == null) {
            onToast(Str.s(R.string.media_file_open_needs_download))
            return
        }
        OpenFile.open(context, local, MediaUrl.displayFileName(item.content, item.fileName))
            ?.let(onToast)
        return
    }
    onViewer(item)
}

/** 用系统浏览器打开一个链接。没有能打开它的应用时如实说，不静默。 */
internal fun openInBrowser(context: Context, url: String, onToast: (String) -> Unit) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { onToast(Str.s(R.string.common_no_app_for_link)) }
}
