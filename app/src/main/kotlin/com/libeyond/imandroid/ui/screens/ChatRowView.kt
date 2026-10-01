package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.CallRecord
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.ReplyNames
import com.libeyond.imandroid.data.SenderNames
import com.libeyond.imandroid.data.SenderRun
import com.libeyond.imandroid.data.SysEvents
import com.libeyond.imandroid.data.ChatSelection
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
    /**
     * 会话内搜索的命中词（已 trim，空串 = 不高亮）。
     *
     * 高亮底色走 `accentSoft`，**不硬编码黄**（SEARCH_DESIGN §13.5，三端同一条）。
     */
    val searchHighlight: String = "",
    /**
     * 本群成员表（显示名→uid），**只给没有 `mention_spans` 的老消息兜底**（按昵称扫文本，有 uid 就可点）。
     * 超级群不下发成员表 → 空表 → 老消息里的 @ 不高亮，与协议里写的降级一致。
     * 新消息一律走片段那条路，与这份表无关。
     */
    val mentionNames: Map<String, String> = emptyMap(),
    /** 群成员角色：uid → owner/admin/member，昵称旁的徽标用。拿不到（超级群）返回 null，退回消息上带的 from_role。 */
    val roleOf: (String) -> String? = { null },
    /** 群成员显示名：uid → 群昵称 > 昵称 > @句柄。排在备注之后、消息上的昵称之前。 */
    val memberNameOf: (String) -> String? = { null },
    /** 我给这个人起的备注（没起返回 null）：发送者名链的第一级，见 [SenderNames]。 */
    val remarkOf: (String) -> String? = { null },
    /** 本窗该发送者最新一条的昵称快照（[SenderNames.latestNicknames]）：成员表查不到时压过这条自己的老快照。 */
    val latestNicknameOf: (String) -> String? = { null },
)

/**
 * 气泡发送者名：备注 > 群成员名 > 本窗该发送者最新快照 > 本条快照 > 好友名（[SenderNames.bubbleName]）。
 * 原先是 `localNameOf ?: memberNameOf ?: fromNickname`——好友名（昵称）压过群昵称，
 * 且超级群成员表只有自己，非好友一律落到落库时的老快照，改了名也不变（2026-09-15）。
 */
private fun ChatRowStyle.senderNameOf(m: MessageEntity): String? = SenderNames.bubbleName(
    remark = remarkOf(m.sender),
    memberName = memberNameOf(m.sender),
    latestSnapshot = latestNicknameOf(m.sender),
    ownSnapshot = m.fromNickname,
    friendNickname = localNameOf(m.sender),
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
    /** 点引用块跳到原消息（按 conv_seq）。 */
    onJumpToSeq: (Long) -> Unit = {},
    /** 点合并转发卡 → 聊天记录详情页（参数是那条的 content）。 */
    onOpenRecord: (String) -> Unit = {},
    /** 点单聊通话记录回拨（是否视频）。 */
    onCallBack: (Boolean) -> Unit = {},
    hiddenTile: Long = 0L,
    /** 多选态的勾选集（`conv_seq → 消息`）；null = 不在多选态。宫格据此逐格画圈。 */
    selection: Map<Long, MessageEntity>? = null,
    /** 多选态下点宫格某一格 = 勾 / 取消勾这一格（走与整行点击同一个入口，上限吐司也在那里）。 */
    onToggleSelect: (MessageEntity) -> Unit = {},
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
        is ChatRow.Album -> {
            // 一组图必然同一个人发的，取首格判断即可；首格若还在传，那就是我发的
            val firstSent = (r.members.first() as? AlbumMember.Sent)?.msg
            val albumMine = firstSent == null || firstSent.sender == myUid
            val albumShowName = showsSenderName(rows, i, myUid, isGroup)
            AlbumBubble(
            isGroup = isGroup,
            // 发送者头与头像列：与下面 Confirmed 分支同一套口径（名字源 / 首条显名 / 段末挂头像）
            senderName = firstSent?.takeIf { !albumMine }?.let { style.senderNameOf(it) },
            showSenderName = albumShowName,
            senderBadge = firstSent?.takeIf { albumShowName }
                ?.let { SenderRun.badgeOf(style.roleOf(it.sender), it.fromRole) },
            reserveAvatarColumn = isGroup && !albumMine,
            showAvatar = showsSenderAvatar(rows, i, myUid, isGroup),
            avatarSeed = firstSent?.sender.orEmpty(),
            // 点群聊对方头像 → 进该成员资料页（与下面 Confirmed 分支同一套口径）
            onAvatarTap = firstSent?.sender?.takeIf { isGroup && !albumMine }?.let { uid -> { onOpenUser(uid) } },
            // **一个宫格里两种格并存**：已确认的正常显示，还在传的那几格压暗底。
            // 状态由每一格自己带（AlbumMember.sending），不再靠"整行是不是待发行"——
            // 靠行类型的话，一批图必然经历"散成单张 → 逐个变确认 → 最后凑回宫格"。
            tiles = r.members.map { m ->
                when (m) {
                    is AlbumMember.Sent -> AlbumTile(
                        m.msg.content, m.msg.contentType, m.msg.duration,
                        thumb = m.msg.thumb, sizeBytes = m.msg.fileSize ?: 0L,
                        mark = ChatSelection.tileMark(selection, m.msg),
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
                (r.members.getOrNull(idx) as? AlbumMember.Sent)?.let {
                    // 多选态下点一格是勾它（不可勾的格 toggle 当没发生），不开查看器
                    if (selection != null) onToggleSelect(it.msg) else onOpenMedia(it.msg)
                }
            },
            selecting = selection != null,
            mine = albumMine,
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
        }
        // 系统消息走居中灰字，不进气泡分支（iOS IMSystemCell / Web .sys-note）。
        // 不用 `when` 卫语句（Kotlin 2.0 仍是实验特性），在分支内早退。
        is ChatRow.Confirmed -> if (r.msg.contentType == ContentType.CALL && CallRecord.parse(r.msg.content)?.isGroup == true) {
            // 群通话记录：居中系统条（不可点、无时间无勾）。发起人 = 消息发送者，名字走与群消息同一条解析链，本人写「你」
            val mine = r.msg.sender == myUid
            SystemNote(text = CallRecord.renderRaw(r.msg.content, mine, if (mine) "" else style.senderNameOf(r.msg).orEmpty()).text)
        } else if (r.msg.contentType == ContentType.SYSTEM) {
            SystemNote(
                text = r.msg.content,
                sysSegments = r.msg.sysSegments,
                // P3：认识的结构化事件按 App 语言重拼分段（人名段不变，照旧本地显示名 + 可点）
                localized = remember(r.msg.sysEvent, r.msg.sysArgs, r.msg.sysSegments) {
                    SysEvents.groupSegments(r.msg.sysEvent, r.msg.sysArgs, r.msg.sysSegments)
                },
                localName = localNameOf,
                onTapUid = onOpenUser,
            )
        } else {
            val m = r.msg
            // 名字头只画在连续段首条；徽标跟着名字走（iOS `isFirstInSenderRun:` + `IMRoleBadge`）
            val showName = showsSenderName(rows, i, myUid, isGroup)
            Bubble(
            // 系统通知单聊（P3）：认识的事件按 App 语言拼多行正文，否则显示服务端中文 content
            text = SysEvents.noticeTextOf(m) ?: m.content,
            msg = m,
            mentionNames = style.mentionNames,
            // 点 @某人 与点系统消息里的名字是同一个去处：他的资料页
            onTapMention = onOpenUser,
            onLongPress = { rect -> onLongPress(m, rect) },
            onOpenMedia = onOpenMedia,
            onOpenRecord = onOpenRecord,
            onCallBack = onCallBack,
            host = host,
            useTls = useTls,
            mine = m.sender == myUid,
            timestamp = m.timestamp,
            // 备注 > 群成员名 > 本窗最新快照 > 本条快照 > 好友名（SenderNames，三端同序）
            senderName = if (m.sender != myUid) {
                style.senderNameOf(m)
            } else {
                null
            },
            showSenderName = showName,
            senderBadge = if (showName) SenderRun.badgeOf(style.roleOf(m.sender), m.fromRole) else null,
            // 群聊两行式引用条（对齐 iOS）：被引用者昵称独占一行。
            // 单聊传 null——只有两个人，写谁的名字都是废话。
            // 快照三档：服务端冻结的 > 本地那条原消息现算 > 「原消息」。
            // 第二档是必需的——ack 回不来冻结快照，自己发的引用消息在自己这侧没有它。
            quoteSnapshot = quoteSnapshotFor(rows, m),
            // 引用块的真缩略与"能不能跳"都来自**本地反查到的那条原消息**
            quoteThumb = originalOf(rows, m.replyToConvSeq ?: 0L)?.thumb,
            // 引用块**只要有原消息号就可点**。此前还要求它已经在渲染窗口里，
            // 于是"翻不到那么早"的原消息连点都点不了——而现在定位这一层会先把窗口撑到
            // 盖得住那一条（ChatLocator），真的不在本地时它会如实说一句。
            onTapQuote = (m.replyToConvSeq ?: 0L)
                .takeIf { it > 0 }
                ?.let { seq -> { onJumpToSeq(seq) } },
            // **绝不退到 uid**：此前本地没备注时原样显示 10 位内部 ID（`localNameOf(it) ?: it`）
            replyFromName = if (isGroup) {
                val original = originalOf(rows, m.replyToConvSeq ?: 0L)
                // **ack 不回带 reply_to_from**（与冻结快照同一族）：自己发的引用消息在自己这侧
                // 这个字段恒空，名字行整个不画——所以退到本地那条原消息的发送者现算
                val from = m.replyToFrom ?: original?.sender
                ReplyNames.quoteFrom(
                    uid = from,
                    myUid = myUid,
                    localName = from?.let(localNameOf),
                    memberName = from?.let(style.memberNameOf),
                    originalNickname = original?.fromNickname,
                )
            } else {
                null
            },
            // 已读双勾：我发的、且对端读位点已越过它
            read = m.sender == myUid && peerReadSeq >= m.convSeq,
            delivered = m.sender == myUid,
            reserveAvatarColumn = isGroup && m.sender != myUid,
            showAvatar = showsSenderAvatar(rows, i, myUid, isGroup),
            avatarSeed = m.sender,
            // 点群聊对方头像 → 进该成员资料页（此前只有 @提及能跳，头像点了没反应）
            onAvatarTap = if (isGroup && m.sender != myUid) { { onOpenUser(m.sender) } } else null,
            loadLinkPreview = loadLinkPreview,
            searchHighlight = style.searchHighlight,
            )
        }
        is ChatRow.Pending -> {
            // 媒体/文件待发行的 content 是本地 content:// URI——按文本画就会在屏幕上
            // 出现一条写着 `content://media/...` 的绿气泡（真机撞见过）
            val isImage = r.msg.contentType == ContentType.IMAGE
            val isVideo = r.msg.contentType == ContentType.VIDEO
            val isFile = r.msg.contentType == ContentType.FILE
            val isVoice = r.msg.contentType == ContentType.VOICE
            val pct = uploadProgress[r.msg.clientMsgId]
            if (r.msg.contentType == ContentType.CALL && CallRecord.parse(r.msg.content)?.isGroup == true) {
                SystemNote(text = CallRecord.renderRaw(r.msg.content, viewerIsSender = true).text)
            } else if (isImage || isVideo) {
                PendingMediaBubble(
                    localUri = r.msg.content,
                    isVideo = isVideo,
                    timestamp = r.msg.createdAt,
                    sending = r.msg.state == SendState.Sending.name,
                    failed = r.msg.state == SendState.Failed.name,
                    progress = pct,
                    onRetry = { onRetry(r.msg.clientMsgId) },
                )
            } else if (isVoice) {
                PendingVoiceBubble(
                    clientMsgId = r.msg.clientMsgId,
                    convId = r.msg.convId,
                    localUri = r.msg.content,
                    durationMs = (r.msg.duration ?: 0).toLong(),
                    waveform = r.msg.waveform,
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
                    pendingType = r.msg.contentType,
                    // 待发气泡的片段只能从待发行取（此时还没有正式消息行）
                    mentionSpansJson = r.msg.mentionSpans,
                    mentionNames = style.mentionNames,
                    onTapMention = onOpenUser,
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
