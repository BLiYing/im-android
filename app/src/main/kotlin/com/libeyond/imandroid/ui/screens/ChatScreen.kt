package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SendHorizontal
import com.libeyond.imandroid.data.ChatEntry
import com.libeyond.imandroid.data.AlbumLayout
import com.libeyond.imandroid.data.AttachItems
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.data.db.SendState
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/** 距顶多少行以内就去加载更早的一页。 */
private const val LOAD_OLDER_THRESHOLD = 3

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    convId: String,
    title: String,
    myUid: String,
    /** 群聊：对方消息要挂发送者头像（UI_SPEC §3）。 */
    isGroup: Boolean,
    /** 本人已读位点，用于未读分割线与首屏定位。 */
    readSeq: Long,
    /** **服务端算出的真实未读数**。判据只认它，见 [ChatEntry.hasUnread]。 */
    unread: Int,
    /** 副标题：在线态 / 「正在输入」。空串不显示。 */
    subtitle: String,
    /** 对端已读位点（单聊）。我发的 conv_seq ≤ 它 → 绿双勾。群聊传 0。 */
    peerReadSeq: Long,
    /** 输入变化时回调，供节流上报 typing。 */
    onTyping: () -> Unit,
    /** 长按一条消息。 */
    /** 长按一条消息，带上气泡在窗口里的矩形（菜单按它定位）。 */
    onLongPress: (MessageEntity, Rect) -> Unit,
    /** 点开媒体查看器。 */
    onOpenMedia: (MessageEntity) -> Unit,
    /** 当前引用的目标；null=没在引用。 */
    replyTo: MessageEntity?,
    onCancelReply: () -> Unit,
    /** 滚到顶部附近时回调，加载更早的消息。 */
    onLoadOlder: () -> Unit,
    /** 点「+」选图片。 */
    onAttach: (AttachItems.Kind) -> Unit,
    /** 点标题进详情（群资料 / 用户资料）。 */
    onOpenInfo: () -> Unit,
    /** 取链接富预览（文本气泡里首个 URL）。由 Host 注入，screen 不持有 IMClient。 */
    loadLinkPreview: suspend (String) -> com.libeyond.imandroid.sdk.api.LinkPreview?,
    /** 媒体地址补全用。 */
    host: String,
    useTls: Boolean,
    rows: List<ChatRow>,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onRetry: (String) -> Unit,
    /** 分片上传进度：clientMsgId → 百分比。没有条目 = 不在分片上传中。 */
    uploadProgress: Map<String, Int>,
    /** 可见即读：把已读位点推到这一条。 */
    onVisibleSeq: (Long) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val listState = rememberLazyListState()

    // —— 首屏定位（CHAT_UX §3）：有未读锚到首条未读，无未读贴底 ——
    // 只跑一次（key 用 convId），**不能与下面的自动贴底合并**：
    // 合并会让「停在首条未读」被贴底当场覆盖掉。
    var didEntryScroll by remember(convId) { mutableStateOf(false) }
    LaunchedEffect(convId, rows.size) {
        if (!didEntryScroll && rows.isNotEmpty()) {
            didEntryScroll = true
            val idx = ChatEntry.entryScrollIndex(rows.map { it.seqOrZero() }, readSeq, unread)
            listState.scrollToItem(idx)   // 瞬时滚动，不用 animate——首屏动画会被用户看成"跳了一下"
        }
    }

    // —— 新内容到达时贴底：只在用户本来就贴着底时 ——
    LaunchedEffect(rows.size) {
        if (!didEntryScroll || rows.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (ChatEntry.shouldAutoScroll(last, rows.size)) {
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    // —— 滚到顶部附近就加载更早的一页 ——
    //
    // 两件事一起做，缺一条都会出问题：
    // ① **在途守卫**：不守的话 rows.size 一变 effect 就再触发，一路把整个会话
    //    （可能十几万条）全加载进来，等于没做窗口。
    // ② **翻页保位**：在顶部插入 N 条后，firstVisibleItemIndex 仍指向同一个**下标**，
    //    而那个下标现在对应的是更早的消息——用户会看到列表凭空跳走。
    //    补偿一律**按同一条消息**（下标 + 新增条数），不按 contentSize 差值。
    var pendingOlder by remember(convId) { mutableStateOf(false) }
    var rowsBeforeLoad by remember(convId) { mutableStateOf(0) }

    LaunchedEffect(rows.size, listState.firstVisibleItemIndex) {
        if (!didEntryScroll || rows.isEmpty()) return@LaunchedEffect

        // 上一页加载回来了 → 把视口按同一条消息补偿回去
        if (pendingOlder && rows.size > rowsBeforeLoad) {
            val added = rows.size - rowsBeforeLoad
            pendingOlder = false
            listState.scrollToItem(
                (listState.firstVisibleItemIndex + added).coerceAtMost(rows.lastIndex),
                listState.firstVisibleItemScrollOffset,
            )
            return@LaunchedEffect
        }

        if (!pendingOlder && listState.firstVisibleItemIndex <= LOAD_OLDER_THRESHOLD) {
            pendingOlder = true
            rowsBeforeLoad = rows.size
            onLoadOlder()
        }
    }

    // —— 可见即读 ——
    // **注意**：程序化滚到底之后紧跟"可见即读"上报，等于替用户把消息读完
    // （十万条未读进一次会话清零就是这么来的，CHAT_UX §3）。
    // 故只在**首屏定位完成后**才上报，且只报真正可见的行。
    LaunchedEffect(rows.size, listState.layoutInfo.visibleItemsInfo.size) {
        if (!didEntryScroll) return@LaunchedEffect
        val maxSeq = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { (rows.getOrNull(it.index) as? ChatRow.Confirmed)?.msg?.convSeq }
            .maxOrNull() ?: return@LaunchedEffect
        onVisibleSeq(maxSeq)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.groupedBackground)
            .systemBarsPadding()
            .imePadding(),
    ) {
        // —— 标题栏 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = d.space3, vertical = d.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                imageVector = Lucide.ArrowLeft,
                contentDescription = "返回",
                modifier = Modifier.size(24.dp).clickable { onBack() },
                colorFilter = ColorFilter.tint(c.accent),
            )
            Spacer(Modifier.width(d.space3))
            Column(Modifier.clickable { onOpenInfo() }) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.textPrimary,
                    maxLines = 1,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (subtitle == "在线") c.online else c.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            // 横向内边距**就是** UI_SPEC §3 的「头像距 cell 左 12」——不要换成别的数，
            // 群头像列靠它凑出 iOS 的 12+30+6=48（Bubbles.kt 那侧不再重复加）。
            modifier = Modifier.fillMaxSize().padding(horizontal = d.chatAvatarLeading),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows.size, key = { rows[it].key }) { i ->
                when (val r = rows[i]) {
                    is ChatRow.DayLabel -> DaySeparator(r.timestamp)
                    is ChatRow.UnreadDivider -> UnreadDividerRow()
                    is ChatRow.Album -> AlbumBubble(
                        tiles = r.msgs.map {
                            AlbumTile(it.content, it.contentType, it.duration)
                        },
                        // 宫格**逐格**点开（iOS 同）：点第 3 格就该看第 3 张，
                        // 整格共用一个回调会让所有格都打开第一张
                        onTapTile = { idx -> r.msgs.getOrNull(idx)?.let(onOpenMedia) },
                        mine = r.msgs.first().sender == myUid,
                        timestamp = r.msgs.last().timestamp,
                        host = host,
                        useTls = useTls,
                        onLongPress = { rect -> onLongPress(r.msgs.first(), rect) },
                    )
                    is ChatRow.PendingAlbum -> AlbumBubble(
                        tiles = r.msgs.map {
                            AlbumTile(it.content, it.contentType, null, sending = true)
                        },
                        mine = true,
                        timestamp = r.msgs.last().createdAt,
                        host = host,
                        useTls = useTls,
                        // 待发的整组还没 conv_seq，长按菜单无从下手（撤回/引用都要 seq）
                        onLongPress = {},
                    )
                    // 系统消息走居中灰字，不进气泡分支（iOS IMSystemCell / Web .sys-note）。
                    // 不用 `when` 卫语句（Kotlin 2.0 仍是实验特性），在分支内早退。
                    is ChatRow.Confirmed -> if (r.msg.contentType == ContentType.SYSTEM) {
                        SystemNote(r.msg.content)
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
        }

        // ↓ 悬浮跳转：离底较远时出现，点了瞬时贴底
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val awayFromBottom = rows.isNotEmpty() && lastVisible in 0 until (rows.size - 1 - ChatEntry.NEAR_BOTTOM_SLACK)
        if (awayFromBottom) {
            val scope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = d.space4, bottom = d.space3)
                    .size(d.jumpButton)
                    .clip(CircleShape)
                    .background(c.surfaceElevated)
                    .clickable {
                        // 瞬时滚动，不用 animate——长列表上动画会滚很久，看着像卡住
                        scope.launch { listState.scrollToItem(rows.size - 1) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = "回到最新",
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            }
        }
        }

        // —— 引用条（正在引用某条消息）——
        if (replyTo != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(c.surface)
                    .padding(horizontal = d.space3, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).height(28.dp).background(c.accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = replyTo.content.take(60).ifBlank { "[媒体]" },
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text("✕", color = c.textTertiary, modifier = Modifier.clickable { onCancelReply() })
            }
        }

        // ➕ 面板与键盘**互斥**（微信/iOS 同款）：展开面板要收键盘，
        // 点输入框要收面板——两个都占着底部空间，同时在场就会把消息列表挤没。
        var attachOpen by remember(convId) { mutableStateOf(false) }
        val keyboard = LocalSoftwareKeyboardController.current
        Composer(
            input = input,
            onInputChange = {
                onInputChange(it)
                if (it.isNotEmpty()) onTyping()
            },
            onSend = onSend,
            onPlus = {
                attachOpen = !attachOpen
                if (attachOpen) keyboard?.hide()
            },
            onInputFocus = { attachOpen = false },
        )
        if (attachOpen) {
            AttachPanel(onPick = { kind ->
                attachOpen = false
                onAttach(kind)
            })
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onPlus: () -> Unit,
    onInputFocus: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 单行态总高 56（UI_SPEC §4，iOS inputBar.heightAnchor 同值）；
            // 多行时允许长高，故用 heightIn(min) 而非 height。
            .heightIn(min = d.inputBarHeight)
            .background(c.surface)
            // 按钮距栏边 8（UI_SPEC §4，iOS plusButton leading）——移动端要给拇指留满宽，
            // 不走 Web 的 --space-4 页面节奏。
            .padding(horizontal = d.inputBarEdge, vertical = d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 点击区 36（与 iOS plusButton 同）；图标本身 24。
        Box(
            modifier = Modifier.size(d.inputControl).clickable { onPlus() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.Plus,
                contentDescription = "更多",
                modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(c.textSecondary),
            )
        }
        Spacer(Modifier.width(d.space2))
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable { onInputFocus() }
                // 输入框圆角**跟随气泡圆角**（外观页可调）——iOS 就是这么做的，
                // 之前写死 20 等于把用户的圆角设置在输入框上吞掉了（UI_SPEC §4）。
                .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                .background(c.pageBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (input.isEmpty()) {
                Text("发送消息…", color = c.textTertiary, fontSize = 15.sp)
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(d.space2))
        Box(
            modifier = Modifier
                .size(d.inputControl)
                .clip(CircleShape)
                .background(if (input.isNotBlank()) c.accent else c.neutralControl)
                .clickable(enabled = input.isNotBlank()) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.SendHorizontal,
                contentDescription = "发送",
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
    }
}


