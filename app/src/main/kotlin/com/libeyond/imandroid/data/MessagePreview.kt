package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 会话列表那一行的**最后一条消息预览文案**。
 *
 * 从 [MessageRepository] 平移出来（2026-09-16，那个文件贴着 600 行硬闸），**行为未改**。
 * 平移时顺带把原先的两份合成一份：`previewOf(MessageData)` 与 `previewOfEntity(MessageEntity)`
 * 逐字同构（前者多写的 `system` 分支落到 `else` 也是回正文，结果一样），
 * 分成两份的**唯一**理由是入参类型不同——而它真正读的只有三个字段，
 * 所以这里收成散参数：两条路（HTTP 快照 / 本地落库）从此不可能分叉。
 *
 * **图说压过占位符**：带 caption 的图片/视频/文件在列表里显示那句话而不是「[图片]」，
 * 同 iOS/Web（口径见 `../IMServer/docs/UI.md` 的会话列表那节）。语音刻意不看 caption。
 */
object MessagePreview {

    fun of(contentType: String, content: String, caption: String?): String = when (contentType) {
        ContentType.TEXT -> content
        ContentType.IMAGE -> caption?.takeIf { it.isNotBlank() } ?: "[图片]"
        ContentType.VIDEO -> caption?.takeIf { it.isNotBlank() } ?: "[视频]"
        ContentType.VOICE -> "[语音]"
        ContentType.FILE -> caption?.takeIf { it.isNotBlank() } ?: "[文件]"
        ContentType.CONTACT -> "[个人名片]"
        ContentType.CHAT_RECORD -> "[聊天记录]"
        // system 与未知类型都回正文：未知类型多半是**新版本加的**消息，
        // 正文至少还能看出个大概，显示成空白才是真的丢信息（PROTOCOL §2「未知要忍」）。
        else -> content
    }
}
