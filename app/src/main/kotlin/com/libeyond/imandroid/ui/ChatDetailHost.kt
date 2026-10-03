package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.sendCard
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.ChatDetailNav
import com.libeyond.imandroid.data.ChatDetailPage
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailActions
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.MuteState
import com.libeyond.imandroid.data.PinnedAt
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.R
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMTextPrompt
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.LocalOpenLink
import com.libeyond.imandroid.ui.components.MuteDurationSheet
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.components.rememberMuteTick
import com.libeyond.imandroid.ui.screens.ChatDetailScreen
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import com.libeyond.imandroid.ui.screens.linkUrlOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * 单聊详情接线层（M4.5-3）。
 *
 * **单聊此前只有「用户资料页」**，那回答的是"这个人是谁"；"这段对话怎么设置"
 * （置顶/免打扰/发过哪些媒体）一直没有地方放。iOS 的 `IMChatDetailViewController`
 * 就是这一层，用户资料是它 push 出去的一页。
 */
@Composable
fun ChatDetailHost(
    client: IMClient,
    conv: ConversationEntity,
    /** 本地好友表，进用户资料页时用来定型关系与备注（避免闪动）。 */
    knownFriends: Map<String, FriendEntry>,
    /** 「搜索」pill：关掉本页、回聊天页开搜索态（本页盖在聊天页之上，只能这么绕）。 */
    onSearchInChat: () -> Unit = {},
    /** 归档长按「定位到聊天」：同上，关掉本页、把 conv_seq 交给聊天页。 */
    onLocateInChat: (Long) -> Unit = {},
    /** 进来先落在哪个页签。查看器的「媒体」钮要直达媒体页签（单聊默认就是它）。 */
    initialTab: DetailTab = DetailTab.Media,
    /** 只当会话媒体库用（查看器右下角「媒体」钮进的那一页）。见 `ChatDetailScreen.galleryOnly`。 */
    galleryOnly: Boolean = false,
    /**
     * 操作排要不要出「消息」pill（对齐 iOS `showsMessagePill`）。
     * 从**外部入口**（群成员/@提及/系统消息里的名字）进来时为 true——这个单聊可能压根没打开过，
     * 需要一个「消息」入口把会话开出来；从聊天页自己点头像进来时为 false（已经在会话里了）。
     */
    showsMessagePill: Boolean = false,
    /** 点「消息」pill：交回调用方去换会话（[showsMessagePill] 为 false 时不会触发）。 */
    onOpenChat: (ConversationEntity) -> Unit = {},
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 「链接」页签点一条在 App 内打开（宿主在 WebLinkHost，iOS `openLink:`）
    val openLink = LocalOpenLink.current
    val owner = client.uid.orEmpty()
    val title = Forward.titleOf(conv)

    var profile by remember(conv.convId) { mutableStateOf(false) }
    // 拆成两个变量（而不是用 `viewing: ConvMediaItem?` 的非空身兼"开不开"）：关闭时只翻
    // `viewingOpen`，`viewingData` 留着不清——退场动画那 300ms 里 `PushTransition` 仍按冻结的
    // `page` 渲染 Media 分支，若这时数据已经被清空就会半路变白（UI_PARITY_IOS.md §4 第 316 行）。
    var viewingOpen by remember(conv.convId) { mutableStateOf(false) }
    var viewingData by remember(conv.convId) { mutableStateOf<ConvMediaItem?>(null) }
    var toast by remember(conv.convId) { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val headerSubtitle = if (conv.isGroup) "" else rememberChatSubtitle(client, conv)
    var askFriend by remember(conv.convId) { mutableStateOf<FriendRequestTarget?>(null) }
    val saveMedia = rememberMediaSaver { toast = it }

    // 好友关系是**异步校正**的：外层传进来那份可能是几分钟前的，而操作排的显隐全靠它
    // （非好友只显「加好友」）。进页重拉一次，别拿旧值摆一排必然 4xx 的按钮。
    var friend by remember(conv.convId) { mutableStateOf(knownFriends[conv.peerUid]) }
    LaunchedEffect(conv.convId, conv.peerUid) {
        if (conv.peerUid.isNotEmpty()) {
            runCatching { client.contacts.friends() }
                .onSuccess { list -> friend = list.firstOrNull { it.userId == conv.peerUid } }
        }
    }
    // 对端权威名片：进页拉一次，并回会话行（对方改了昵称 / 头像，信息页与会话列表都跟着新；
    // 与 IMProgram `loadPeerProfile`、im-web `loadPeerCard` 同口径）。单聊才有；自己 / 空 uid 不拉；
    // 不限好友（非好友也能开这一页）；失败静默，保持旧值。
    LaunchedEffect(conv.convId, conv.peerUid) {
        if (!conv.isGroup && conv.peerUid.isNotEmpty() && conv.peerUid != owner) {
            runCatchingCancellable { client.contacts.card(conv.peerUid) }
                .onSuccess { client.messages.applyPeerCard(conv.peerUid, it) }
                .onFailure { IMLog.tag("IM.Detail").w("peer_card_failed") }
        }
    }
    // 「更多」里那几件要二次确认 / 要填一句话的事
    var confirm by remember(conv.convId) { mutableStateOf<DetailMoreAction?>(null) }
    var reporting by remember(conv.convId) { mutableStateOf(false) }
    var sharing by remember(conv.convId) { mutableStateOf(false) }

    var pinned by remember(conv.convId) { mutableStateOf(conv.pinnedAt > 0) }
    var pinnedAt by remember(conv.convId) { mutableStateOf(conv.pinnedAt) }
    var muted by remember(conv.convId) { mutableStateOf(conv.muted) }
    var muteUntil by remember(conv.convId) { mutableStateOf(conv.muteUntil) }
    var markedUnread by remember(conv.convId) { mutableStateOf(conv.markedUnread) }
    // `conv` 是打开聊天那一刻的快照，之后在本页或别的设备改的置顶/免打扰/标未读它都不知道——
    // 进页拉一次服务端设置（同 iOS IMChatDetailViewController、本端 GroupInfoSettings.load）。
    // 不拉的话：置顶打开→退出重进又显示关（2026-09-29 真机），保存时还会把旧的 marked_unread 写回去。
    LaunchedEffect(conv.convId) {
        runCatching { client.conversationsApi.settings(conv.convId) }
            .onSuccess { s ->
                pinned = s.pinnedAt > 0; pinnedAt = s.pinnedAt
                muted = s.muted; muteUntil = s.muteUntil; markedUnread = s.markedUnread
            }
            .onFailure { IMLog.tag("IM.Detail").w("conv_settings_load_failed") }
    }
    var muteSheetOpen by remember(conv.convId) { mutableStateOf(false) }
    var remark by remember(conv.convId) { mutableStateOf(conv.peerRemark) }
    var editingRemark by remember(conv.convId) { mutableStateOf(false) }
    // 定时免打扰到期刷新（NOTIFICATIONS_P1_DESIGN §4.4）：本页只关心这一个会话，喂单元素表即可。
    val muteTick = rememberMuteTick(listOf(conv.copy(muted = muted, muteUntil = muteUntil)))
    val mutedNow = MuteState.isMutedNow(muted, muteUntil, muteTick)

    // 归档长按菜单（媒体/文件/语音/链接四格共用；接线在 ArchiveActionsHost）
    var archiveMenuFor by remember(conv.convId) { mutableStateOf<ArchiveTarget?>(null) }
    var archiveMenuAnchor by remember(conv.convId) { mutableStateOf(Rect.Zero) }
    /** 归档要转发的那一项（长按菜单与查看器「更多」共用这一份状态，见 ArchiveActionsHost 的注释）。 */
    var archiveForward by remember(conv.convId) { mutableStateOf<ArchiveTarget?>(null) }

    var tab by remember(conv.convId) { mutableStateOf(initialTab) }
    // 归档取数与「链接」本地扫都收在这两个 helper 里（群资料那侧共用同一份）
    val archive = rememberConvArchive(client, conv.convId, tab)
    // 这两份只在各自页签上订阅本地消息表（理由见 rememberLocalScan）
    val linkMessages = rememberLinkMessages(client, conv.convId, active = tab == DetailTab.Links)
    // 语音页签的波形：服务端归档接口不回带，从本地消息表按 conv_seq 兜底（见 rememberVoiceWaveforms）
    val voiceWaveforms = rememberVoiceWaveforms(client, conv.convId, active = tab == DetailTab.Voice)

    val page = ChatDetailNav.current(mediaOpen = viewingOpen, profileOpen = profile)
    // 返回键一处派发（同 GroupInfoHost；理由见 ChatDetailPage 的注释）
    BackHandler {
        when (page) {
            ChatDetailPage.Media -> viewingOpen = false
            ChatDetailPage.Profile -> profile = false
            ChatDetailPage.Detail -> onBack()
        }
    }

    /**
     * 会话设置是**整体替换**三项：改一项也要把另外两项原样带回，否则会顺手清掉。
     * @param newMuteUntil 定时免打扰到期毫秒——**只有时长菜单选中/点「取消免打扰」才传**，
     *   置顶开关这条路留 `null`（省略），让服务端按 PROTOCOL §6.10 缺省规则保留原到期时间。
     */
    fun pushSettings(newPinned: Boolean, newMuted: Boolean, newMuteUntil: Long? = null) {
        pinnedAt = PinnedAt.next(newPinned, pinnedAt)
        val sendPinnedAt = pinnedAt
        scope.launch {
            runCatching {
                client.conversationsApi.updateSettings(
                    conv.convId,
                    pinnedAt = sendPinnedAt,
                    muted = newMuted,
                    markedUnread = markedUnread,
                    muteUntil = newMuteUntil,
                )
            }.onFailure { IMLog.tag("IM.Detail").w("conv_settings_failed") }
            client.messages.refreshConversations()
        }
    }

    // Media / Profile / Detail 三态统一走 iOS 式 push/pop 滑动转场（UI_PARITY_IOS.md §4 第 316 行）。
    // Media 这支能接进来，靠的是上面把 `viewing` 拆成了 `viewingOpen`/`viewingData`——退场动画
    // 期间 `page` 虽已冻结成 Media，`viewingData` 也还留着最后那张图，不会半路变白。
    PushTransition(targetState = page, depthOf = { it.depth }) { state ->
        when (state) {
            ChatDetailPage.Media -> viewingData?.let { m ->
                // 翻页 / 「更多」/ 转发都在 ArchiveViewer.kt 里，与群资料那侧共用
                ArchiveMediaViewer(
                    client = client, convId = conv.convId, isGroup = false, iAmManager = false,
                    archive = archive, current = m, scope = scope,
                    // 查看器标题＝会话名（iOS `IMMediaPagerViewController.conversationTitle`）
                    title = conv.title,
                    onSave = saveMedia,
                    onForwardPicker = { archiveForward = it },
                    onLocateInChat = { seq -> viewingOpen = false; onLocateInChat(seq) },
                    onChanged = { archive.reload() },
                    onToast = { toast = it },
                    onClose = { viewingOpen = false },
                )
            }
            ChatDetailPage.Profile -> {
                val f = knownFriends[conv.peerUid]
                UserProfileHost(
                    client = client,
                    userId = conv.peerUid,
                    knownRelation = f?.status.orEmpty(),
                    seed = UserCard(
                        userId = conv.peerUid,
                        nickname = conv.title,
                        avatarUrl = conv.avatarUrl,
                        remark = remark,
                    ),
                    onSendMessage = { profile = false },
                    // 在资料页里改的备注也要回填详情页自己的 `remark` state，否则退回来后
                    // 标题/语音发送者名/拉黑确认框标题仍显编辑前的旧值（`/code-review` 抓出）
                    onRemarkChanged = { v -> remark = v },
                    onBack = { profile = false },
                )
            }
            ChatDetailPage.Detail -> {
                ChatDetailScreen(
                    conv = conv,
                    title = title,
                    handle = knownFriends[conv.peerUid]?.handle.orEmpty(),
                    subtitle = headerSubtitle,
                    onCopyUsername = {
                        val bare = knownFriends[conv.peerUid]?.handle.orEmpty().removePrefix("@")
                        if (bare.isEmpty()) toast = Str.s(R.string.chat_detail_no_username)
                        else {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(bare)) // 裸句柄，可直接粘进搜索框
                            toast = Str.s(R.string.chat_detail_username_copied)
                        }
                    },
                    remark = remark,
                    pinned = pinned,
                    muteValueText = if (mutedNow) MuteState.untilText(muteUntil, muteTick) else stringResource(R.string.common_off),
                    tab = tab,
                    onTabChange = { tab = it },
                    archive = archive.items,
                    linkMessages = linkMessages,
                    loading = archive.loading,
                    hasMore = archive.hasMore,
                    onLoadMore = { archive.loadMore() },
                    onOpenArchive = { item ->
                        openArchiveItem(client, context, item, onToast = { toast = it }) {
                            viewingData = it; viewingOpen = true
                        }
                    },
                    onLongPressArchive = { t, r -> archiveMenuFor = t; archiveMenuAnchor = r },
                    onOpenLink = { url -> openLink?.invoke(url) },
                    onTogglePinned = { v -> pinned = v; pushSettings(v, muted) },
                    onOpenMuteSheet = { muteSheetOpen = true },
                    // 页内弹窗编辑，不跳页（对齐 iOS `editRemark`；弹窗组件与用户资料页共用，见 RemarkEditDialog）
                    onSetRemark = { editingRemark = true },
                    onOpenProfile = { profile = true },
                    actions = DetailActions.pillsFor(
                        isGroup = false,
                        isSystemPeer = DetailActions.isSystemPeer(conv.peerUid),
                        peerIsFriend = friend?.status == FriendEntry.ACCEPTED,
                        showsMessagePill = showsMessagePill,
                    ),
                    moreItems = DetailActions.moreFor(
                        isGroup = false,
                        isSystemPeer = DetailActions.isSystemPeer(conv.peerUid),
                        iAmOwner = false,
                        peerBlocked = friend?.blocked == true,
                        peerIsFriend = friend?.status == FriendEntry.ACCEPTED,
                    ),
                    onAction = { a ->
                        when (a) {
                            // 与 iOS 同：pill 点了回聊天页进搜索态（SEARCH_DESIGN §4）
                            DetailAction.Search -> onSearchInChat()
                            // 通话界面整套由 im-rtc 的 Kit 接管；拨不出去（没配置 / 没上线）才回一句原因。
                            DetailAction.Call -> RtcCall.placeSingle(conv.peerUid, video = false)?.let { toast = it }
                            DetailAction.Video -> RtcCall.placeSingle(conv.peerUid, video = true)?.let { toast = it }
                            DetailAction.GroupCall -> Unit // 单聊不会出这个 pill
                            DetailAction.AddFriend -> askFriend = FriendRequestTarget(conv.peerUid, remark.ifBlank { conv.title })
                            // showsMessagePill=false（从聊天页自己点头像进来）时这条 pill 根本不会出现
                            DetailAction.Message -> onOpenChat(conv)
                            // 「更多」走下面单独的 onMore 回调，这里不会出现
                            DetailAction.More -> Unit
                        }
                    },
                    onMore = { m ->
                        when (m) {
                            DetailMoreAction.ShareContact -> sharing = true
                            DetailMoreAction.Report -> reporting = true
                            DetailMoreAction.Unblock -> scope.launch {
                                runCatching { client.contacts.unblock(conv.peerUid) }
                                    .onSuccess { toast = Str.s(R.string.friend_block_undone); friend = friend?.copy(blocked = false) }
                                    .onFailure { toast = it.userMessage(Str.s(R.string.common_action_failed)) }
                            }
                            // 其余都要二次确认（拉黑/清空/删好友都不可撤销或代价大）
                            else -> confirm = m
                        }
                    },
                    host = client.host,
                    useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
                    // 语音行的发送者名。单聊只有两个人：我自己显「你自己」（同 iOS），对方显本机显示名
                    senderNameOf = { uid -> if (uid == owner) Str.s(R.string.chat_detail_you) else remark.ifBlank { conv.title } },
                    waveformOf = { seq -> voiceWaveforms[seq] },
                    galleryOnly = galleryOnly,
                    isSystemPeer = DetailActions.isSystemPeer(conv.peerUid),
                    onBack = onBack,
                )
            }
        }
    }

    if (editingRemark) {
        RemarkEditDialog(
            current = remark,
            placeholderNickname = conv.title,
            onDismiss = { editingRemark = false },
            onConfirm = { v ->
                editingRemark = false
                scope.launch {
                    runCatching { client.contacts.setRemark(conv.peerUid, v) }
                    client.messages.refreshConversations()
                    remark = v
                }
            },
        )
    }

    // 定时免打扰时长菜单（NOTIFICATIONS_P1_DESIGN §4.1/§4.2）：「消息免打扰」行点了弹它；
    // 已免打扰时最上面多一项红色「取消免打扰」（用于「把 8 小时改成永久」这类调整）。
    if (muteSheetOpen) {
        MuteDurationSheet(
            convTitle = title,
            showUnmute = mutedNow,
            onUnmute = {
                muted = false; muteUntil = 0; muteSheetOpen = false
                pushSettings(pinned, false)
            },
            onSelect = { d ->
                val until = d.muteUntil()
                muted = true; muteUntil = until; muteSheetOpen = false
                pushSettings(pinned, true, until)
            },
            onDismiss = { muteSheetOpen = false },
        )
    }

    // —— 「更多」的二次确认 / 填理由 / 选会话 ——
    val peerName = remark.ifBlank { conv.title }
    when (confirm) {
        DetailMoreAction.Block -> IMConfirmDialog(
            title = stringResource(R.string.chat_detail_block_confirm_title, peerName),
            message = stringResource(R.string.chat_detail_block_confirm_message),
            confirmText = stringResource(R.string.common_block),
            onConfirm = {
                confirm = null
                scope.launch {
                    runCatching { client.contacts.block(conv.peerUid) }
                        .onSuccess { toast = Str.s(R.string.common_blocked); friend = friend?.copy(blocked = true) }
                        .onFailure { toast = it.userMessage(Str.s(R.string.friend_block_failed_toast)) }
                }
            },
            onDismiss = { confirm = null },
        )
        DetailMoreAction.ClearHistory -> IMConfirmDialog(
            title = stringResource(R.string.chat_detail_clear_history_confirm_title),
            message = stringResource(R.string.chat_detail_clear_history_message_dm),
            confirmText = stringResource(R.string.chat_clear_ok),
            onConfirm = {
                confirm = null
                scope.launch {
                    // **只删本机**（同 iOS `clearMessagesForConv:`）：服务端没有、也不该有
                    // 「替所有人删历史」的接口
                    client.repo.clearConversation(owner, conv.convId)
                    // 详情页的媒体 / 文件 / 语音页签是服务端归档分页、已加载那份还留着刚清掉的项：重载一次（请求带清空位点，
                    // 滤掉位点以内；iOS 清空后页签当场清空）
                    archive.reload()
                    toast = Str.s(R.string.chat_detail_clear_history_done)
                }
            },
            onDismiss = { confirm = null },
        )
        DetailMoreAction.RemoveFriend -> IMConfirmDialog(
            title = stringResource(R.string.chat_detail_remove_friend_confirm_title, peerName),
            message = stringResource(R.string.chat_detail_remove_friend_confirm_message),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                confirm = null
                scope.launch {
                    runCatching { client.contacts.remove(conv.peerUid) }
                        .onSuccess {
                            toast = Str.s(R.string.friend_delete_done)
                            // **不退页**：重拉关系后本页自然切成非好友视图，用户当场看得到关系已变
                            runCatching { client.contacts.friends() }
                                .onSuccess { l -> friend = l.firstOrNull { it.userId == conv.peerUid } }
                        }
                        .onFailure { toast = it.userMessage(Str.s(R.string.net_fallback_delete_failed)) }
                }
            },
            onDismiss = { confirm = null },
        )
        else -> Unit
    }

    if (reporting) {
        IMTextPrompt(
            title = stringResource(R.string.chat_detail_report_confirm_title, peerName), initial = "", maxLen = 200, multiline = true,
            onDismiss = { reporting = false },
            onConfirm = { reason ->
                reporting = false
                scope.launch {
                    // target_type=user：举报**这个人**。聊天页长按那个是 target_type=message，
                    // 两个入口互补，别合并（iOS 合并消息侧两项时差点丢掉人侧那个）。
                    runCatching { client.contacts.report("user", conv.peerUid, conv.convId, reason) }
                        .onSuccess { toast = Str.s(R.string.chat_detail_report_submitted) }
                        .onFailure { toast = it.userMessage(Str.s(R.string.net_fallback_report_failed)) }
                }
            },
        )
    }

    if (sharing) {
        val convs by client.repo.observeConversations(owner).collectAsState(initial = emptyList())
        ForwardPickerScreen(
            conversations = convs.filter { it.convId != conv.convId },
            onCancel = { sharing = false },
            onToast = { toast = it },
            onConfirm = { targets ->
                sharing = false
                scope.launch {
                    // **写进名片的是公开名**（不是备注）——这段 JSON 会原样发给第三个人。
                    // iOS 与 im-web 各为此出过一次事故（IMServer docs/UI.md 隐私红线）。
                    val json = CardContent.encodeContact(
                        uid = conv.peerUid,
                        username = friend?.username.orEmpty(),
                        nickname = friend?.let { DisplayName.publicNameOfFriend(it) }.orEmpty(),
                        avatarUrl = conv.avatarUrl,
                    )
                    for (t in targets) {
                        runCatching {
                            client.messages.sendCard(
                                convId = t.convId,
                                to = if (t.isGroup) "" else t.peerUid,
                                contentType = com.libeyond.imandroid.sdk.protocol.ContentType.CONTACT,
                                json = json,
                            )
                        }
                    }
                    toast = if (targets.size > 1) Str.p(R.plurals.common_sent_to_chats, targets.size, targets.size) else Str.s(R.string.common_sent)
                }
            },
        )
    }

    // 归档长按菜单 + 转发选择页（与群资料那侧共用同一份接线）
    ArchiveActionsHost(
        client = client,
        convId = conv.convId,
        isGroup = false,
        // 单聊没有管理员这回事；「为所有人删除」只对自己发的开（ArchiveActions 里判）
        iAmManager = false,
        // 菜单点完就关，删除/隐藏那几次请求不能挂在它身上（见 ArchiveActionsHost 的 scope 注释）
        scope = scope,
        target = archiveMenuFor,
        anchor = archiveMenuAnchor,
        onLocateInChat = { seq -> archiveMenuFor = null; onLocateInChat(seq) },
        onChanged = { archive.reload() },
        onToast = { toast = it },
        onForwardPicker = { archiveForward = it },
        onDismiss = { archiveMenuFor = null },
    )

    // 归档转发选择页（长按菜单与查看器「更多」共用）
    archiveForward?.let { t ->
        ArchiveForwardPicker(
            client = client, convId = conv.convId, target = t, scope = scope,
            onDismiss = { archiveForward = null },
            onToast = { toast = it },
        )
    }

    FriendRequestPrompt(client, askFriend, onDismiss = { askFriend = null }, onToast = { toast = it })
    // toast 放最后：它是一层 fillMaxSize 的浮层，画在页面之前会被页面盖住
    toast?.let { t -> IMToast(t) { toast = null } }
}
