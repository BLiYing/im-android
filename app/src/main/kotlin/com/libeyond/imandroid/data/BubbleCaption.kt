package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.contentTypeAllowsCaption

/** 图说（caption）在气泡里画在哪（PROTOCOL §4.1「caption 展示与降级」）。 */
enum class CaptionPlacement {
    /** 不画：没有图说 / 撤回墓碑 / 这类消息本就不收图说。 */
    None,

    /** 贴边媒体（图/视频）下方：媒体贴着气泡边，图说要自己补回内边距。 */
    UnderMedia,

    /** 文件卡下方：文件气泡不贴边，内边距气泡已经给了，只隔开文件行。 */
    UnderFile,
}

/**
 * 图说画不画、画在哪的**唯一判据**。
 *
 * 为什么单独抽出来：此前这条判据挂在「是不是贴边媒体」上（`flushMedia && caption 非空`），
 * 而文件气泡不贴边——于是**文件文的图说整段不画**，气泡里只剩一个文件卡，
 * iOS / Web 同一条消息都有字（2026-09-15 用户在「libeyond群」里撞见）。
 * 「贴不贴边」是排版问题，「有没有图说」是内容问题，两件事不该共用一个布尔。
 */
object BubbleCaption {

    fun placementOf(contentType: String?, caption: String?, recalled: Boolean): CaptionPlacement = when {
        recalled || caption.isNullOrBlank() || contentType == null -> CaptionPlacement.None
        // 服务端只对 image/video/file 收图说（挂在别的类型上会被丢弃），脏数据也不画
        !contentTypeAllowsCaption(contentType) -> CaptionPlacement.None
        contentType == ContentType.FILE -> CaptionPlacement.UnderFile
        else -> CaptionPlacement.UnderMedia
    }
}
