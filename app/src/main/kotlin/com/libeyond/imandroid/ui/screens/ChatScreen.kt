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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.draw.alpha
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
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.TopBarAvatar
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
    /**
     * 「回到最新」。**不是"滚到列表底部"**——窗口停在历史时，最新那条根本不在这一窗里，
     * 只滚列表回不去（`MESSAGE_WINDOW_DESIGN` §4.2：跳转即换窗，回最新要换回尾窗）。
     */
    onJumpToLatest: () -> Unit = {},
    /**
     * 「回到最新」按钮该不该亮。判据在 `ChatWindows.showsJumpToLatest`：
     * **窗口停在历史时必须亮**——跳转不产生滚动事件，而且跳过去的那一段常常整屏放得下，
     * 连"离底很远"的兜底都轮不到。这一条 iOS 与 Web 都栽过（CLIENT_PARITY「有回程」那条的 ③）。
     */
    showsJumpToLatest: (awayFromBottom: Boolean) -> Boolean = { it },
    /** 点「+」选图片。 */
    onAttach: (AttachItems.Kind) -> Unit,
    /**
     * 进会话详情。**入口是右上角那个会话头像**（UI_SPEC §4.5）——
     * 此前只能点标题进，而"标题可以点"这件事界面上没有任何提示，等于没有入口。
     */
    onOpenInfo: () -> Unit,
    /** 会话头像（右上角那个）。空串则显示首字母色块。 */
    avatarUrl: String,
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
    /**
     * 正被长按（菜单开着）的那条的 `conv_seq`；`0` = 没有。
     *
     * **那一行要整行隐形**：长按菜单会在原位重绘一份气泡浮在压暗层之上，
     * 底下这份真气泡透过半透明的压暗层还看得见，两份错开一点点就是用户说的「重叠感」。
     * iOS 的 `UITargetedPreview` 会自动把原视图藏起来，本端得自己藏。
     */
    menuForSeq: Long,
    /** 可见即读：把已读位点推到这一条。 */
    onVisibleSeq: (Long) -> Unit,
    /** 系统消息里名字段的本地显示名（备注/群昵称）。返回 null 用服务端给的公开昵称。 */
    localNameOf: (String) -> String? = { null },
    /** 点系统消息里的名字 → 进那个人的资料页（对齐 iOS `onTapUID`）。 */
    onOpenUser: (String) -> Unit = {},
    /**
     * 要定位到的 `conv_seq`（`0` = 没有）。**由 Host 驱动**——目标常在渲染窗口之外，
     * 得先把窗口撑到覆盖它（`ChatHost.locate`），本页只负责"它出现在 rows 里之后滚过去"。
     * 滚到了就回调 [onLocateConsumed] 归零。
     */
    locateSeq: Long = 0,
    onLocateConsumed: () -> Unit = {},
    /** 点引用块 → 请求定位到原消息。判断能不能跳、跳不了说什么都在 Host（它才查得到本地库）。 */
    onJumpToSeq: (Long) -> Unit = {},
    // —— 会话内搜索态（SEARCH_DESIGN §4）：顶栏换搜索框、底栏换命中导航条 ——
    searchOpen: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
    searchNavLabel: String = "",
    searchNotice: String = "",
    searchCanPrev: Boolean = false,
    searchCanNext: Boolean = false,
    onSearchPrev: () -> Unit = {},
    onSearchNext: () -> Unit = {},
    /** 命中词（已 trim）。空串 = 不高亮。 */
    searchHighlight: String = "",
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

    // 「回到最新」按下之后的待办：等换窗后的那一批 rows 到了再贴底。
    // **不能在点击回调里直接滚**——那时 rows 还是旧那一窗（换窗是异步的）。
    var pendingScrollToBottom by remember(convId) { mutableStateOf(false) }

    // —— 新内容到达时贴底：只在用户本来就贴着底时（或刚点过「回到最新」）——
    LaunchedEffect(rows.size) {
        if (!didEntryScroll || rows.isEmpty()) return@LaunchedEffect
        if (pendingScrollToBottom) {
            pendingScrollToBottom = false
            listState.scrollToItem(rows.size - 1) // 瞬时，不用 animate（跨窗那一跳距离没有意义）
            return@LaunchedEffect
        }
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
        // —— 标题栏（全局共用 IMTopBar，规格见 UI_SPEC §4.5）——
        // 搜索态整条换成搜索框（同 iOS：顶栏进 searchMode，不是在标题下面再加一行）
        if (searchOpen) {
            ChatSearchTopBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onCancel = onCloseSearch,
            )
        } else {
            IMTopBar(
                title = title,
                subtitle = subtitle,
                subtitleAccent = subtitle == "在线",
                onLeft = onBack,
                // 标题也保留可点（iOS 就是点标题进详情），但**可见入口是右边那个头像**
                onTitleClick = onOpenInfo,
                avatar = TopBarAvatar(
                    label = title,
                    seed = convId,
                    url = avatarUrl,
                    onClick = onOpenInfo,
                ),
            )
        }

        // —— 定位到某条消息（引用块跳转 / 搜索命中）——
        // **瞬时滚动不用 animateScrollToItem**：长列表上动画会滚很久，看着像卡住
        //（与 ↓ 悬浮按钮同一条纪律，也与 im-web `jumpToSeq` 同——见那边的坑）。
        // 跳到后短暂高亮那一行，否则用户不知道停在了哪。
        //
        // 目标不在 rows 里时**什么都不做、继续等**：Host 已经确认过本地有这条并把
        // 渲染窗口撑大了，下一帧 rows 长出来这个 effect 会再跑一次。
        // "跳不了"的判断与提示归 Host——只有它查得到本地库。
        var highlightSeq by remember(convId) { mutableStateOf(0L) }
        LaunchedEffect(locateSeq, rows.size) {
            if (locateSeq <= 0) return@LaunchedEffect
            val idx = rowIndexOfSeq(rows, locateSeq)
            if (idx < 0) return@LaunchedEffect
            listState.scrollToItem(idx)
            centerItem(listState, idx)
            // 先点亮再归零：归零会换掉本 effect 的 key 把它取消，
            // 高亮的熄灭因此**不能**写在这里（写这儿就会一直亮着）。
            highlightSeq = locateSeq
            onLocateConsumed()
        }
        LaunchedEffect(highlightSeq) {
            if (highlightSeq <= 0) return@LaunchedEffect
            kotlinx.coroutines.delay(1200)
            highlightSeq = 0
        }

        // 列表与长按预览共用同一份渲染参数（见 ChatRowStyle 的注释：分两份写栽过两次）
        val rowStyle = ChatRowStyle(
            myUid = myUid,
            isGroup = isGroup,
            host = host,
            useTls = useTls,
            peerReadSeq = peerReadSeq,
            uploadProgress = uploadProgress,
            localNameOf = localNameOf,
            loadLinkPreview = loadLinkPreview,
            searchHighlight = searchHighlight,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            // 横向内边距**就是** UI_SPEC §3 的「头像距 cell 左 12」——不要换成别的数，
            // 群头像列靠它凑出 iOS 的 12+30+6=48（Bubbles.kt 那侧不再重复加）。
            modifier = Modifier.fillMaxSize().padding(horizontal = d.chatAvatarLeading),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows.size, key = { rows[it].key }) { i ->
                val r0 = rows[i]
                // 菜单开着的那一行整行隐形（保留占位，列表不跳）。
                // **宫格例外**：只隐被长按的那一格（浮起来的也只有那一格），
                // 整行隐会让旁边几张跟着消失——那不是 iOS 的样子。
                val hidden = menuForSeq > 0 &&
                    (r0 as? ChatRow.Confirmed)?.msg?.convSeq == menuForSeq
                val highlighted = highlightSeq > 0 && when (r0) {
                    is ChatRow.Confirmed -> r0.msg.convSeq == highlightSeq
                    is ChatRow.Album -> r0.sent.any { it.convSeq == highlightSeq }
                    else -> false
                }
                Box(
                    Modifier
                        .alpha(if (hidden) 0f else 1f)
                        .background(if (highlighted) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent),
                ) {
                ChatRowView(
                    rows = rows,
                    i = i,
                    style = rowStyle,
                    onLongPress = onLongPress,
                    onOpenMedia = onOpenMedia,
                    onOpenUser = onOpenUser,
                    onRetry = onRetry,
                    onJumpToSeq = onJumpToSeq,
                    // 宫格：长按的那一格自己隐形（整行不隐，其余格仍在原位）
                    hiddenTile = if (r0 is ChatRow.Album) menuForSeq else 0L,
                )
                }
            }
        }

        // ↓ 悬浮跳转：离底较远、**或窗口停在历史**时出现
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val awayFromBottom = rows.isNotEmpty() && lastVisible in 0 until (rows.size - 1 - ChatEntry.NEAR_BOTTOM_SLACK)
        if (showsJumpToLatest(awayFromBottom)) {
            val scope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = d.space4, bottom = d.space3)
                    .size(d.jumpButton)
                    .clip(CircleShape)
                    .background(c.surfaceElevated)
                    .clickable {
                        // 先请宿主换回尾窗（历史窗里没有"最新那条"可滚）。
                        // **贴底不能在这里做**：换窗是异步的，此刻 rows 还是旧那一窗，
                        // 滚过去只会落在旧窗的末尾（真机撞见：从会话开头点↓，落在半空中）。
                        // 记一个待办，等新的一窗到了再贴底。
                        onJumpToLatest()
                        pendingScrollToBottom = true
                        // 本来就在尾窗里（只是离底远）时不会有新数据到达，直接滚
                        scope.launch { listState.scrollToItem((rows.size - 1).coerceAtLeast(0)) }
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

        // 搜索态：底部换成命中导航条（输入栏与引用条都让位——搜索时发不了消息，
        // 摆一个能打字的输入框只会让人以为搜的是"要发的内容"）。
        if (searchOpen) {
            ChatSearchNavBar(
                label = searchNavLabel,
                notice = searchNotice,
                canPrev = searchCanPrev,
                canNext = searchCanNext,
                onPrev = onSearchPrev,
                onNext = onSearchNext,
            )
            return@Column
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
                // **与气泡内引用块同一套渲染**：引用时看到的那句话，
                // 发出去以后在气泡里显示的必须是同一句（此前这里直接截 content，
                // 引用一张图会显示 `/uploads/req-xxx__原名.jpg`）。
                QuoteBlock(
                    snapshot = replyPreviewOf(
                        replyTo.contentType, replyTo.content, replyTo.fileName, replyTo.caption,
                    ),
                    fromName = if (isGroup) replyTo.fromNickname else null,
                    // 输入栏这条引用条与气泡里的引用块**是同一个组件、同一句话、同一张缩略**
                    // （iOS 的 IMMediaPlaceholder 也是这两处共用）。少传一个 thumb 就会出现
                    // 「引的时候只有图标、发出去之后才有缩略」这种说不清的差别。
                    thumb = replyTo.thumb,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
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

/**
 * 一条消息的引用快照该显示什么。
 *
 * 三档，优先级从高到低：
 * ① 服务端**发送时冻结**的 `reply_snapshot`（原消息后续被删/撤回仍可展示）；
 * ② 本地那条原消息**现算**——`ack` 只回 5 个字段，冻结快照回不来，
 *    所以自己发的引用消息在自己这一侧只有这一档可用；
 * ③ 都没有 → 「原消息」（同 iOS `IMBubbleCell` 的兜底文案）。
 *
 * `replyToConvSeq <= 0` 表示这条不是引用，返回 null 让调用方整块不画。
 */
/**
 * 被引用消息在**本地**的那一条（宫格成员也算）。找不到 = 翻不到那么早 / 已被删。
 *
 * 引用块要的两样东西都从它来：**真缩略图**（快照是冻结的文字，不带 thumb）
 * 与**跳转目标**。所以这两件事天然是同一块——iOS 也是先反查再决定画什么/能不能点。
 */
internal fun originalOf(rows: List<ChatRow>, seq: Long): MessageEntity? {
    if (seq <= 0) return null
    for (r in rows) {
        when (r) {
            is ChatRow.Confirmed -> if (r.msg.convSeq == seq) return r.msg
            is ChatRow.Album -> r.sent.firstOrNull { it.convSeq == seq }?.let { return it }
            else -> Unit
        }
    }
    return null
}

/** 这一行在列表里的下标（跳转要用）。宫格里的某一格算它所在的那一行。 */
internal fun rowIndexOfSeq(rows: List<ChatRow>, seq: Long): Int {
    if (seq <= 0) return -1
    return rows.indexOfFirst { r ->
        when (r) {
            is ChatRow.Confirmed -> r.msg.convSeq == seq
            is ChatRow.Album -> r.sent.any { it.convSeq == seq }
            else -> false
        }
    }
}

internal fun quoteSnapshotFor(rows: List<ChatRow>, msg: MessageEntity): String? {
    val seq = msg.replyToConvSeq ?: return null
    if (seq <= 0) return null
    msg.replySnapshot?.takeIf { it.isNotBlank() }?.let { return it }
    val original = originalOf(rows, seq) ?: return "原消息"
    return replyPreviewOf(original.contentType, original.content, original.fileName, original.caption)
}

/**
 * 把第 [index] 行滚到视口**中间**。
 *
 * `scrollToItem` 是把目标顶到视口**顶端**，而 `CHAT_UX.md §3.1` 的三端契约是**居中**
 * ——顶端对齐时目标上方的上下文一行都看不到，"跳到了但不知道跳到哪"。
 * iOS 用 `UITableViewScrollPositionMiddle`，Web 用 `scrollIntoView({block:"center"})`，
 * Compose 没有对应参数，只能先顶上去再补一段偏移。
 *
 * 目标比视口还高时不补（`delta <= 0`），补了反而把它的开头推出屏幕。
 * 靠边的行由 `scrollBy` 自己夹住，不必特判。
 */
private suspend fun centerItem(listState: LazyListState, index: Int) {
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
    val delta = (viewport - info.size) / 2f
    if (delta > 0f) listState.scrollBy(-delta)
}
