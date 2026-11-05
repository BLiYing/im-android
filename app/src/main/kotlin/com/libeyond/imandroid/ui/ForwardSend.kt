package com.libeyond.imandroid.ui

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.FavoritePick
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.MessagePreview
import com.libeyond.imandroid.data.SelectionActions
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.protocol.ContentType
import java.util.UUID

/**
 * 把若干条消息**逐条**转发到若干个会话，回一句给用户看的话。
 *
 * **逐条、逐会话串行发**：服务端对 `send_msg` 有限流，9 个会话 × 100 条并发打过去必然撞墙。
 *
 * 抽出来是因为它有好几个调用方（聊天页长按转发 / 多选「逐条转发」/ 详情页归档长按转发 / 收藏长按转发；
 * 「从收藏发送」走下面的 [sendFavoritesTo]，同一个 `MessageService.forward`）。各写一遍的代价不是
 * 重复代码，是**限流纪律与 `forwardFrom` 口径会分叉**——而分叉的表现是"从某个入口转发出去的
 * 消息少了『转发自 X』"，编译与测试都看不出来。
 *
 * 合并转发（一张 `chat_record` 卡片）不走这里，见 `ChatSelectionActions.kt` 的 [ForwardPickerLayer]。
 *
 * @param expiredSkipped 调用方已经滤掉的失效媒体条数，拼进回执里如实告诉用户。
 */
internal suspend fun forwardMessages(
    client: IMClient,
    msgs: List<MessageEntity>,
    targets: List<ConversationEntity>,
    expiredSkipped: Int = 0,
): String {
    if (msgs.isEmpty() || targets.isEmpty()) return ""
    val owner = client.uid.orEmpty()
    val myName = client.myPublicName()
    for (t in targets) {
        val to = if (t.isGroup) "" else t.peerUid
        // 同一原相册选了 ≥2 张 → 每个目标会话各发一个新 group_id，收端重新聚成宫格（iOS/Web 同）
        val albums = SelectionActions.regroupAlbums(msgs) { "alb-${UUID.randomUUID()}" }
        for (m in msgs) {
            client.messages.forward(
                msg = m, toConvId = t.convId, to = to,
                origin = Forward.originOf(m, owner, myName),
                groupId = albums[m.convSeq],
            )
        }
    }
    return SelectionActions.forwardDoneText(targets.size, expiredSkipped)
}

/**
 * 「从收藏发送」：把选中的收藏发进**当前会话**，回一句回执（iOS `sendPickedFavorite:` 逐条 `forwardEchoContent:`）。
 *
 * 与上面那条同一个 `MessageService.forward`：媒体复用原 URL、元数据与「转发自」口径只有一份。
 * **失效媒体跳过**（同收藏页「转发」的拦截，发出去对端必 404），跳过几条如实写进回执。
 * 收藏之间没有相册关系，不重新分组。
 */
internal suspend fun sendFavoritesTo(client: IMClient, msgs: List<MessageEntity>, conv: ConversationEntity): String {
    val owner = client.uid.orEmpty()
    val myName = client.myPublicName()
    val to = if (conv.isGroup) "" else conv.peerUid
    val live = msgs.filterNot { m ->
        SelectionActions.isExpiredMedia(m) { url, v -> client.downloads.stateOf(url, v).phase == DownloadPhase.Expired }
    }
    for (m in live) {
        client.messages.forward(msg = m, toConvId = conv.convId, to = to, origin = Forward.originOf(m, owner, myName))
    }
    return FavoritePick.sentText(live.size, msgs.size - live.size)
}

/**
 * 分享选择页确认面板里的内容预览（SHARE_PICKER_UX_SKETCH ④），复用已有摘要口径：
 * 合并转发 = 「[聊天记录]」；逐条转发多条 = 「[逐条转发] N 条消息」；单条 = 这一条的摘要——
 * 文件写文件名、名片写「[个人名片] 昵称」、聊天记录写记录标题，其余同会话列表预览（[MessagePreview]）。
 */
internal fun forwardPreview(msgs: List<MessageEntity>, record: String?, owner: String): String {
    if (record != null) return Str.s(R.string.preview_chat_record)
    if (msgs.size > 1) return Str.p(R.plurals.share_preview_each_messages, msgs.size, msgs.size)
    val m = msgs.singleOrNull() ?: return ""
    return when (m.contentType) {
        ContentType.FILE -> Str.s(R.string.quote_snapshot_file_named, MediaUrl.displayFileName(m.content, m.fileName.orEmpty()))
        ContentType.CONTACT -> CardContent.contactPreview(m.content)
        ContentType.CHAT_RECORD -> CardContent.parseRecord(m.content, maxLines = 0)?.title ?: Str.s(R.string.preview_chat_record)
        else -> MessagePreview.of(m.contentType, m.content, m.caption, viewerIsSender = m.sender == owner)
    }
}
