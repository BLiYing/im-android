package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.SendState
import com.libeyond.imandroid.sdk.api.LinkPreview
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 聊天页**一行**的全部渲染参数。
 *
 * 存在的理由只有一个：**长按菜单的原位重绘与列表本身必须是同一段代码**。
 * 两处各画各的时，本端连着栽了两次——长按九宫格重绘成一张大图（对不上原位）、
 * 长按带链接的文本时富预览卡整个不出现（"不是整个气泡浮起"）。两次都是
 * 「预览那一份漏传了某个参数」，编译不报、测试也测不到，只有真机看得见。
 */
internal data class ChatRowStyle(
    val myUid: String,
    val isGroup: Boolean,
    val host: String,
    val useTls: Boolean,
    /** 对端已读位点（单聊双勾）。群聊传 0。 */
    val peerReadSeq: Long,
    /** 分片上传进度：clientMsgId → 百分比。 */
    val uploadProgress: Map<String, Int> = emptyMap(),
    /** 系统消息里名字段的本地显示名（备注/群昵称）。 */
    val localNameOf: (String) -> String? = { null },
    /** 取链接富预览。**预览重绘时也要传**——不传就是"气泡少了半截"。 */
    val loadLinkPreview: (suspend (String) -> LinkPreview?)? = null,
)

/**
 * 画 `rows[i]` 这一行。列表与长按预览共用。
 *
 * @param hiddenTile 九宫格里要隐形的那一格（长按单格时，那一格由浮层接管）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRowView(
    rows: List<ChatRow>,
    i: Int,
    style: ChatRowStyle,
    onLongPress: (MessageEntity, Rect) -> Unit = { _, _ -> },
    onOpenMedia: (MessageEntity) -> Unit = {},
    onOpenUser: (String) -> Unit = {},
    onRetry: (String) -> Unit = {},
    hiddenTile: Long = 0L,
) {
    val myUid = style.myUid
    val isGroup = style.isGroup
    val host = style.host
    val useTls = style.useTls
    val peerReadSeq = style.peerReadSeq
    val uploadProgress = style.uploadProgress
    val localNameOf = style.localNameOf
    val loadLinkPreview = style.loadLinkPreview
    when (val r = rows[i]) {
        is ChatRow.DayLabel -> DaySeparator(r.timestamp)
        is ChatRow.UnreadDivider -> UnreadDividerRow()
        is ChatRow.Album -> AlbumBubble(
            isGroup = isGroup,
            // **一个宫格里两种格并存**：已确认的正常显示，还在传的那几格压暗底。
            // 状态由每一格自己带（AlbumMember.sending），不再靠"整行是不是待发行"——
            // 靠行类型的话，一批图必然经历"散成单张 → 逐个变确认 → 最后凑回宫格"。
            tiles = r.members.map { m ->
                when (m) {
                    is AlbumMember.Sent -> AlbumTile(
                        m.msg.content, m.msg.contentType, m.msg.duration,
                        thumb = m.msg.thumb, sizeBytes = m.msg.fileSize ?: 0L,
                    )
                    is AlbumMember.Sending -> AlbumTile(
                        m.msg.content, m.msg.contentType, null,
                        sending = m.msg.state == SendState.Sending.name,
                        // 分片上传的百分比（视频/大文件）；图片整包上传时为 null → 转圈
                        progress = uploadProgress[m.msg.clientMsgId],
                        failed = m.msg.state == SendState.Failed.name,
                        thumb = m.msg.thumb,
                    )
                }
            },
            // 宫格**逐格**点开（iOS 同）：点第 3 格就该看第 3 张，
            // 整格共用一个回调会让所有格都打开第一张。
            // **还在传的那格点不开**——本地 uri 能显示但查看器要服务端地址。
            onTapTile = { idx ->
                (r.members.getOrNull(idx) as? AlbumMember.Sent)?.let { onOpenMedia(it.msg) }
            },
            // 一组图必然同一个人发的，取首格判断即可；首格若还在传，那就是我发的
            mine = when (val f = r.members.first()) {
                is AlbumMember.Sent -> f.msg.sender == myUid
                is AlbumMember.Sending -> true
            },
            timestamp = when (val l = r.members.last()) {
                is AlbumMember.Sent -> l.msg.timestamp
                is AlbumMember.Sending -> l.msg.createdAt
            },
            host = host,
            useTls = useTls,
            // 长按**哪一格就带哪一条**（此前恒传 first()，撤回/引用会作用到第一张上）。
            // 还在传的那格无从下手（撤回/引用都要 conv_seq），不响应。
            onLongPressTile = { idx, rect ->
                (r.members.getOrNull(idx) as? AlbumMember.Sent)?.let { onLongPress(it.msg, rect) }
            },
            hiddenIndex = if (hiddenTile > 0L) {
                r.members.indexOfFirst { (it as? AlbumMember.Sent)?.msg?.convSeq == hiddenTile }
            } else {
                -1
            },
        )
        // 系统消息走居中灰字，不进气泡分支（iOS IMSystemCell / Web .sys-note）。
        // 不用 `when` 卫语句（Kotlin 2.0 仍是实验特性），在分支内早退。
        is ChatRow.Confirmed -> if (r.msg.contentType == ContentType.SYSTEM) {
            SystemNote(
                text = r.msg.content,
                sysSegments = r.msg.sysSegments,
                localName = localNameOf,
                onTapUid = onOpenUser,
            )
        } else Bubble(
            text = r.msg.content,
            msg = r.msg,
            onLongPress = { rect -> onLongPress(r.msg, rect) },
            onOpenMedia = onOpenMedia,
            host = host,
            useTls = useTls,
            mine = r.msg.sender == myUid,
            timestamp = r.msg.timestamp,
            senderName = if (r.msg.sender != myUid) r.msg.fromNickname else null,
            // 群聊两行式引用条（对齐 iOS）：被引用者昵称独占一行。
            // 单聊传 null——只有两个人，写谁的名字都是废话。
            // 快照三档：服务端冻结的 > 本地那条原消息现算 > 「原消息」。
            // 第二档是必需的——ack 回不来冻结快照，自己发的引用消息在自己这侧没有它。
            quoteSnapshot = quoteSnapshotFor(rows, r.msg),
            replyFromName = if (isGroup) {
                r.msg.replyToFrom?.let { localNameOf(it) ?: it.takeIf { u -> u.isNotBlank() } }
            } else {
                null
            },
            // 已读双勾：我发的、且对端读位点已越过它
            read = r.msg.sender == myUid && peerReadSeq >= r.msg.convSeq,
            delivered = r.msg.sender == myUid,
            reserveAvatarColumn = isGroup && r.msg.sender != myUid,
            showAvatar = showsSenderAvatar(rows, i, myUid, isGroup),
            avatarSeed = r.msg.sender,
            loadLinkPreview = loadLinkPreview,
        )
        is ChatRow.Pending -> {
            // 媒体/文件待发行的 content 是本地 content:// URI——按文本画就会在屏幕上
            // 出现一条写着 `content://media/...` 的绿气泡（真机撞见过）
            val isImage = r.msg.contentType == ContentType.IMAGE
            val isVideo = r.msg.contentType == ContentType.VIDEO
            val isFile = r.msg.contentType == ContentType.FILE
            val pct = uploadProgress[r.msg.clientMsgId]
            if (isImage || isVideo) {
                PendingMediaBubble(
                    localUri = r.msg.content,
                    isVideo = isVideo,
                    timestamp = r.msg.createdAt,
                    sending = r.msg.state == SendState.Sending.name,
                    failed = r.msg.state == SendState.Failed.name,
                    progress = pct,
                    onRetry = { onRetry(r.msg.clientMsgId) },
                )
            } else if (isFile) {
                PendingFileBubble(
                    // 待发行还没有服务端地址，名字只能来自本地 meta
                    fileName = r.msg.fileName.orEmpty()
                        .ifBlank { MediaUrl.displayFileName(r.msg.content) },
                    fileSize = r.msg.fileSize,
                    timestamp = r.msg.createdAt,
                    sending = r.msg.state == SendState.Sending.name,
                    failed = r.msg.state == SendState.Failed.name,
                    progress = pct,
                    onRetry = { onRetry(r.msg.clientMsgId) },
                )
            } else {
                Bubble(
                    text = r.msg.content,
                    mine = true,
                    timestamp = r.msg.createdAt,
                    senderName = null,
                    sending = r.msg.state == SendState.Sending.name,
                    failed = r.msg.state == SendState.Failed.name,
                    onRetry = { onRetry(r.msg.clientMsgId) },
                )
            }
        }
    }
}
