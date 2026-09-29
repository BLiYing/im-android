package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.data.ChatSelection
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
import android.os.SystemClock
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalFocusManager
import com.libeyond.imandroid.data.ChatScroll
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
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
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.TopBarAvatar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    /** 一段语音录完（松手发送 / 锁定态自动发送）。文件已经在应用私有目录，Host 负责真正上传+发帧。 */
    onSendVoice: (file: java.io.File, durationMs: Int, waveform: String?) -> Unit = { _, _, _ -> },
    /** 一次性提示（语音录制的各种边界提示：太短/权限/达上限……）。由 Host 注入，screen 不持有 toast 状态。 */
    onToast: (String) -> Unit = {},
    /** 输入栏上方的内联层（@成员面板 / 粘贴图预览条）。由 Host 注入——screen 不持有 IMClient。 */
    composerAbove: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    /**
     * 正文之外还有东西可发（粘贴条上挂着待发图）。见 [Composer] 的同名参数：
     * 只看正文的话，粘了图却一个字没打时发送键是灰的，那张图发不出去。
     */
    extraSendable: Boolean = false,
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
    /** 当前「来自」筛选的发件人显示名；null = 没在筛选（仅群聊有意义）。 */
    searchFromLabel: String? = null,
    onOpenSearchFrom: () -> Unit = {},
    onClearSearchFrom: () -> Unit = {},
    onOpenSearchCalendar: () -> Unit = {},
    /** 👤 候选面板开着没有 + 候选列表——贴在命中导航条上方，见 [SearchFromPanel]。 */
    searchFromPickerOpen: Boolean = false,
    searchFromCandidates: List<SearchSenderCandidate> = emptyList(),
    onPickSearchFrom: (SearchSenderCandidate) -> Unit = {},
    /** 本群成员表（显示名→uid）——只给没有 mention_spans 的老消息兜底（见 [ChatRowStyle.mentionNames]）。 */
    mentionNames: Map<String, String> = emptyMap(),
    /** 群成员角色 uid → owner/admin/member（发送者徽标）。见 [ChatRowStyle.roleOf]。 */
    roleOf: (String) -> String? = { null },
    /** 群成员显示名 uid → 群昵称/昵称/@句柄。发送者名、引用块、回复条共用。 */
    memberNameOf: (String) -> String? = { null },
    remarkOf: (String) -> String? = { null }, // 发送者名链（SenderNames），见 ChatRowStyle.remarkOf
    latestNicknameOf: (String) -> String? = { null }, // 同上，见 ChatRowStyle.latestNicknameOf
    /** 点合并转发卡 → 聊天记录详情页。 */
    onOpenRecord: (String) -> Unit = {},
    /** 点单聊通话记录 → 按原类型回拨（是否视频）。 */
    onCallBack: (Boolean) -> Unit = {},
    /**
     * 多选态：`conv_seq → 消息`；**null = 不在多选态**。
     * 判据与写入口在 `data/ChatSelection.kt`（按 conv_seq 记且连消息一起存，理由见那里）。
     */
    selection: Map<Long, MessageEntity>? = null,
    onToggleSelect: (MessageEntity) -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onForwardSelected: () -> Unit = {},
    /** 举报钮：可举报时点它 [onReportSelected]，灰着被点 [onReportBlocked]（说清为什么不能举报）。 */
    onReportSelected: () -> Unit = {},
    onReportBlocked: () -> Unit = {},
    onFavoriteSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    /**
     * 行数据两路（已确认 / 待发）都从库里读到了——**空会话也算读到**。
     * 首屏定位要等它：先到一路就定位，另一路到了行序就变了，落点跟着错。
     */
    rowsReady: Boolean = true,
    /** 出箱里新冒出一条（自己刚发的）。Host 在窗口停在历史时换回尾窗，贴底归本页。 */
    onOutgoingEcho: () -> Unit = {},
    /**
     * 被会话详情 / 群资料盖住了。那两页盖在本页之上、本页**不出组合**（返回保位靠这个，见 MainScreen），
     * 所以盖住期间要自己收住副作用：不报已读（用户看的不是这一页）、交出输入焦点（否则键盘盖到详情页上）。
     */
    covered: Boolean = false,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val listState = rememberLazyListState()
    val marks = remember(convId) { ChatScrollMarks() }
    val focusManager = LocalFocusManager.current
    val listDragged by listState.interactionSource.collectIsDraggedAsState()

    // ➕ 面板与键盘**互斥**（微信/iOS 同款）：展开面板要收键盘，弹键盘要收面板——
    // 两个都占着底部空间，同时在场就会把消息列表挤没。
    // 「弹键盘收面板」认键盘本身、不认输入框的点击：点在文本框正中时外层那个 clickable 收不到。
    var attachOpen by remember(convId) { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) attachOpen = false }
    // 多选 / 搜索态不画面板，但 attachOpen 若还挂着，chatListTaps 会把每一下都当「只收面板」吃掉——行勾选点了没反应
    LaunchedEffect(selection != null, searchOpen) { if (selection != null || searchOpen) attachOpen = false }
    LaunchedEffect(covered) { if (covered) focusManager.clearFocus() }

    // 键盘 / 引用条 / 面板改了视口高度：变化前贴着底就重新贴（iOS keyboardWillChange）
    KeepBottomOnResize(listState)

    // —— 滚动时序整组（首屏定位 / 贴底 / 跟底 / 翻页保位 / 可见即读）——
    // 2026-09-16 整段平移到 `ChatScroll.kt`：它们四条读写同一份 marks、彼此有先后与互斥，
    // 必须待在一起；而"消息列表什么时候滚到哪"与"这一页长什么样"是两件事。
    ChatListSync(
        convId = convId,
        listState = listState,
        marks = marks,
        rows = rows,
        rowsReady = rowsReady,
        readSeq = readSeq,
        unread = unread,
        listDragged = listDragged,
        covered = covered,
        onLoadOlder = onLoadOlder,
        onOutgoingEcho = onOutgoingEcho,
        onVisibleSeq = onVisibleSeq,
    )

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
        } else if (selection != null) {
            // 多选态的标题栏：左「取消」+ 中「已选择 N 条」。**不画头像/详情入口**——
            // 多选期间点进详情页会把勾选态丢掉，那是纯粹的误触来源（同 iOS 换掉整条导航栏）
            IMTopBar(
                title = ChatSelection.titleOf(selection.size),
                onLeft = onCancelSelection,
                leftLabel = stringResource(R.string.common_cancel),
            )
        } else {
            IMTopBar(
                title = title,
                subtitle = subtitle,
                // subtitle 由调用方传入（在线状态文案），与它比对的判据必须用**同一个键**渲出的文案，
                // 否则英文界面下 subtitle 永远是 "Online" 而不是 "在线"，绿色高亮会失效
                // （上游若也改用 R.string.common_online 才成立，见本批报告）
                subtitleAccent = subtitle == stringResource(R.string.common_online),
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
        // key 用 rows 本身而非 rows.size：换到一个**条数相同**的锚点窗时 size 不变，目标进来了也不会再跑
        LaunchedEffect(locateSeq, rows) {
            if (locateSeq <= 0) return@LaunchedEffect
            val idx = rowIndexOfSeq(rows, locateSeq)
            if (idx < 0) return@LaunchedEffect
            // 跳转压过「刚点过 ↓」的贴底待办，并让跟底 / 翻页补偿让路一小段
            marks.stickUntil = 0L
            marks.locatingUntil = SystemClock.uptimeMillis() + ChatScroll.STICK_BOTTOM_ARM_MS
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
            mentionNames = mentionNames,
            roleOf = roleOf,
            memberNameOf = memberNameOf,
            remarkOf = remarkOf,
            latestNicknameOf = latestNicknameOf,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            // 横向内边距**就是** UI_SPEC §3 的「头像距 cell 左 12」——不要换成别的数，
            // 群头像列靠它凑出 iOS 的 12+30+6=48（Bubbles.kt 那侧不再重复加）。
            modifier = Modifier
                .fillMaxSize()
                .chatListTaps(
                    panelOpen = attachOpen,
                    onClosePanel = { attachOpen = false },
                    onDismissKeyboard = { focusManager.clearFocus() },
                )
                // chatScrollbar 要排在 padding **之前**：画的时候按到这一步为止的尺寸算右边界，
                // 排在 padding 后面会让它跟着内容一起缩进 12dp，贴不到屏幕真正的边（细滚动条应像
                // iOS/Web 系统白送的那种，紧贴视口边缘，不随内容内边距内移）。
                .chatScrollbar(listState, c.textTertiary.copy(alpha = 0.4f))
                .padding(horizontal = d.chatAvatarLeading),
            // 行距 5、最后一条距输入栏 3：iOS 的间距全长在 cell 里（顶 2 底 3），换成列表的说法就是这三个数
            contentPadding = PaddingValues(top = d.chatListPaddingTop, bottom = d.chatListPaddingBottom),
            verticalArrangement = Arrangement.spacedBy(d.chatRowGap),
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
                // 多选态：可勾的行左侧画圈、整行改成"点一下勾选"。
                // 不可勾的行（系统提示/撤回墓碑/未确认本地件）**不画圈也不响应**，
                // 与 iOS `canEditRowAtIndexPath` 对 NO 的行不画圈同口径。
                val selMsg = (r0 as? ChatRow.Confirmed)?.msg?.takeIf { ChatSelection.selectable(it) }
                val selecting = selection != null
                Row(
                    Modifier
                        .alpha(if (hidden) 0f else 1f)
                        .background(if (highlighted) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .then(
                            if (selecting && selMsg != null) {
                                Modifier.clickable { onToggleSelect(selMsg) }
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                if (selecting) {
                    if (selMsg != null) {
                        SelectionCheck(selected = selection.containsKey(selMsg.convSeq))
                    } else {
                        Spacer(Modifier.width(22.dp)) // 占位，让可勾与不可勾的行左缘对齐
                    }
                    SelectionGutterSpacer()
                }
                ChatRowView(
                    rows = rows,
                    i = i,
                    style = rowStyle,
                    onLongPress = onLongPress,
                    onOpenMedia = onOpenMedia,
                    onOpenUser = onOpenUser,
                    onRetry = onRetry,
                    onJumpToSeq = onJumpToSeq,
                    onOpenRecord = onOpenRecord,
                    onCallBack = onCallBack,
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
                        // 先请宿主换回尾窗（历史窗里没有"最新那条"可滚）。换窗是异步的，此刻 rows 还是
                        // 旧那一窗（真机撞见：从会话开头点↓，落在半空中），所以记一个**带保质期**的贴底，
                        // 新的一窗在保质期内到了，上面那个 effect 会再贴一次。
                        // **保质期不能省**：已经在尾窗时换窗不产生新的 rows，不失效的待办会一直挂着，
                        // 等用户滚上去读历史时来一条新消息，被当成"刚点过 ↓"一把甩到底。
                        onJumpToLatest()
                        marks.stickUntil = SystemClock.uptimeMillis() + ChatScroll.STICK_BOTTOM_ARM_MS
                        // 本来就在尾窗里（只是离底远）时不会有新数据到达，直接贴
                        scope.launch { stickToBottom(listState) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = stringResource(R.string.chat_jump_to_latest),
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            }
        }
        }

        // 搜索态：底部换成命中导航条（输入栏与引用条都让位——搜索时发不了消息，
        // 摆一个能打字的输入框只会让人以为搜的是"要发的内容"）。
        if (searchOpen) {
            // 候选面板贴在导航条**上方**（同 iOS `searchFromPanel` 的位置），不是弹 sheet
            if (searchFromPickerOpen) {
                SearchFromPanel(candidates = searchFromCandidates, onPick = onPickSearchFrom)
            }
            ChatSearchNavBar(
                label = searchNavLabel,
                notice = searchNotice,
                canPrev = searchCanPrev,
                canNext = searchCanNext,
                onPrev = onSearchPrev,
                onNext = onSearchNext,
                showsFromFilter = isGroup,
                fromLabel = searchFromLabel,
                onOpenFrom = onOpenSearchFrom,
                onClearFrom = onClearSearchFrom,
                onOpenCalendar = onOpenSearchCalendar,
            )
            return@Column
        }

        // —— 回复条（正在引用某条消息）：点条跳原消息，下滑 / ✕ 取消 ——
        ReplyBar(
            replyTo = replyTo,
            myUid = myUid,
            isGroup = isGroup,
            convTitle = title,
            localNameOf = localNameOf,
            memberNameOf = memberNameOf,
            onJump = onJumpToSeq,
            onCancel = onCancelReply,
        )

        // 多选态：底部换成动作栏，输入栏整个不画（同 iOS：多选期间隐藏输入栏显示工具栏）
        if (selection != null) {
            SelectionBar(
                selected = selection,
                myUid = myUid,
                onForward = onForwardSelected,
                onReport = onReportSelected,
                onReportBlocked = onReportBlocked,
                onFavorite = onFavoriteSelected,
                onDelete = onDeleteSelected,
            )
            return@Column
        }

        // ➕ 面板与键盘互斥，面板开关态声明在顶上（列表的轻点也要读它）
        val keyboard = LocalSoftwareKeyboardController.current
        Composer(
            convId = convId,
            input = input,
            onSendVoice = onSendVoice,
            onToast = onToast,
            onInputChange = {
                // **只在正文真的变了时**上报「正在输入」。改成 TextFieldValue 之后，
                // 光标移动（点一下中间改错别字）也会走这个回调——那时对端会看到一次
                // 凭空的「正在输入…」（2026-09-09 `/code-review` 抓出的回归）。
                val typed = it.text != input.text
                onInputChange(it)
                if (typed && it.text.isNotEmpty()) onTyping()
            },
            above = composerAbove,
            extraSendable = extraSendable,
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
