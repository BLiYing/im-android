package com.libeyond.imandroid.ui

import com.libeyond.imandroid.ui.screens.MentionPanel
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.sendTyping
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.libeyond.imandroid.data.distinctSenders
import com.libeyond.imandroid.data.extendWindowOlder
import com.libeyond.imandroid.data.observeWindow
import com.libeyond.imandroid.ui.screens.ChatRowStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import com.libeyond.imandroid.data.AttachItems
import com.libeyond.imandroid.data.SenderNames
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import com.libeyond.imandroid.ui.components.IMToast
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.ChatWindow
import com.libeyond.imandroid.data.ChatWindows
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.ui.screens.ChatCalendarDialog
import com.libeyond.imandroid.ui.screens.ChatScreen
import com.libeyond.imandroid.ui.screens.SearchSenderCandidate
import com.libeyond.imandroid.ui.screens.buildChatRows
import kotlinx.coroutines.launch

/** typing 上报节流：每次按键都发是错的，服务端要给全体成员中继。 */
private const val TYPING_THROTTLE_MS = 3_000L

/**
 * 聊天页的接线层：读库、算副标题、发消息、管 watch。
 * [ChatScreen] 保持纯展示（CODING_STYLE §7②）。
 */
@Composable
fun ChatHost(
    client: IMClient,
    conv: ConversationEntity,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    /**
     * 从详情页/群资料带回来的待办（开搜索 / 定位到某条）。
     * 做过一次即回调 [onArmConsumed] 复位，否则每次重组都会再做一遍。
     */
    arm: ChatArm = ChatArm(),
    onArmConsumed: () -> Unit = {},
    /** 被会话详情 / 群资料盖住了（本页仍在组合里，返回时列表原位不动，见 MainScreen）。 */
    covered: Boolean = false,
    /** 查看器「媒体」钮：打开本会话详情的媒体页签（iOS 查看器的媒体库入口）。 */
    onOpenMediaGallery: () -> Unit = {},
    /** 群成员资料页点「发消息」→ 换成与该成员的单聊（同 `GroupInfoHost` 的 `onOpenChat`）。 */
    onOpenChat: (ConversationEntity) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val owner = client.uid.orEmpty()
    // 值 + 光标：@提及要靠光标算「正在输入的 @查询词」，裸 String 算不出来
    var input by remember(conv.convId) { mutableStateOf(TextFieldValue("")) }
    /** 粘进输入框的待发图片（机制与端差异见 `data/PasteImage.kt` 与 [PasteImageBar]）。 */
    val paste = rememberPasteImages(conv.convId)

    /** 多选态（M4-3）。判据在 `data/ChatSelection.kt`，状态在 [ChatSelectionController]。 */
    val sel = rememberChatSelection(conv.convId)

    // —— 转发（M4-3）——
    // 待转发的任务（null = 没在转发）：逐条 or 合并，见 ChatSelectionActions.kt 的 ForwardJob
    var forwarding by remember(conv.convId) { mutableStateOf<ForwardJob?>(null) }
    /** 选图中（覆盖在聊天页之上的自建相册页；无权限时它自己会降级到系统选择器）。 */
    // toast 要声明在下面那些 launcher 回调之前——回调里会赋值
    var toast by remember(conv.convId) { mutableStateOf<String?>(null) }
    // 存相册：权限分支与三段文案都在 rememberMediaSaver 里，这里只拿到一个可调的函数
    val saveMedia = rememberMediaSaver { toast = it }
    // 分片上传进度（视频/文件）。内存态，随进程消亡——上传本来也不跨进程续传。
    val uploadProgress by client.messages.uploadProgress.state.collectAsState()
    var picking by remember(conv.convId) { mutableStateOf(false) }
    /** 选联系人发名片中（null = 不在选）。 */
    var pickingFriend by remember(conv.convId) { mutableStateOf<List<FriendEntry>?>(null) }
    /** 「从收藏发送」选择页开着。 */
    var pickingFavorites by remember(conv.convId) { mutableStateOf(false) }
    /** 正在全屏查看的媒体（null = 没在看）。 */
    var viewing by remember(conv.convId) { mutableStateOf<MessageEntity?>(null) }
    // 点系统消息里的名字进的资料页
    var openUser by remember(conv.convId) { mutableStateOf<String?>(null) }
    /** 聊天记录详情页栈 + 页内查看器。嵌套记录往里点就压一层，返回弹一层。 */
    val recordNav = rememberChatRecordNav(conv.convId)
    var friendsByUid by remember(conv.convId) { mutableStateOf<Map<String, FriendEntry>>(emptyMap()) }
    LaunchedEffect(conv.convId) {
        runCatching { client.contacts.friends() }.onSuccess { l -> friendsByUid = l.associateBy { it.userId } }
    }
    // 语音转文字失败提示（REST 失败 / WS status=failed 共用同一条文案，见 VoiceTranscriber.errors 的 KDoc）。
    // code review 抓出：这个 SharedFlow 建好之后从没被订阅过——识别失败时面板悄悄收起，用户不知道发生了什么。
    LaunchedEffect(conv.convId) {
        client.voiceTranscriber.errors.collect { toast = it }
    }
    var menuFor by remember(conv.convId) { mutableStateOf<MessageEntity?>(null) }
    /** 成员表（群资料里拉）：名字、角色、头像，uid 为键。超级群只有我自己。 */
    var memberNames by remember(conv.convId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var memberAvatars by remember(conv.convId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 多选底栏的转发/举报/收藏（M4-3/M4-4）。要在 BackHandler 之前：返回键先关「逐条/合并」选择单
    val selActions = rememberSelectionActions(
        client, conv, sel, SelectionPeople(friendsByUid, memberNames, memberAvatars),
        onToast = { toast = it }, onOpenForward = { forwarding = it },
    )

    // 渲染窗口（MESSAGE_WINDOW_DESIGN §4）。两态：贴最新的尾窗 / 钉在某段历史的锚点窗。
    // **不能无界**——13 万条的会话整窗构造对象会把聊天页渲染成空白（2026-09-07 实测）。
    var window by remember(conv.convId) {
        mutableStateOf<ChatWindow>(ChatWindow.Tail(ChatWindows.TAIL_LIMIT))
    }

    // —— 定位与会话内搜索 ——
    // 顺序有讲究：locator 先建（search 要用它当跳转出口），两者都要在 BackHandler 之前
    // ——返回键第一层关的是搜索态。
    val connected = client.socket.state.collectAsState().value == ConnState.Connected
    val locator = rememberChatLocator(
        client = client,
        convId = conv.convId,
        onOpenWindow = { window = it },
        onToast = { toast = it },
    )
    val search = rememberChatSearch(
        client = client,
        convId = conv.convId,
        online = connected,
        // 拒绝原因由搜索那侧接管（写进搜索条上方那一行）——搜索态下键盘占着下半屏，
        // 吐司恰好落在键盘背后，等于没提示。
        onLocate = { seq, refuse -> locator.locate(seq, refuse) },
    )
    // 📅 日历跳转：独立状态机（不影响搜索命中集），复用同一个 locator 出口
    val calendar = rememberChatCalendar(
        client = client,
        convId = conv.convId,
        online = connected,
        onLocate = { seq, refuse -> locator.locate(seq, refuse) },
        onLocateEarliest = { refuse -> locator.locateEarliest(refuse) },
        onToast = { toast = it },
    )
    // 👤「来自」候选：面板一开才查 uid 去重集（不是每次进搜索态都查一遍库）；
    // 名字/头像**不进这个 effect**——单独 remember 派生，friendsByUid/memberNames 稍后才拉到时
    // （群资料是异步的）面板还开着的话也能跟着刷新，不必再开一次面板重新查一遍库。
    var searchFromUids by remember(conv.convId) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(search.fromPickerOpen, conv.convId, owner) {
        if (!search.fromPickerOpen || owner.isEmpty()) return@LaunchedEffect
        searchFromUids = client.repo.distinctSenders(owner, conv.convId)
    }
    val searchFromCandidates = remember(searchFromUids, friendsByUid, memberNames, memberAvatars) {
        searchFromUids.map { uid ->
            val name = friendsByUid[uid]?.let { DisplayName.ofFriend(it) }
                ?: memberNames[uid]
                ?: uid
            SearchSenderCandidate(uid, name, friendsByUid[uid]?.avatarUrl ?: memberAvatars[uid].orEmpty())
        }
    }
    // 详情页/群资料带回来的待办（见 ChatArm 的注释：那两页关掉之后才轮得到这里）
    LaunchedEffect(arm) {
        if (arm.isEmpty) return@LaunchedEffect
        if (arm.openSearch) search.begin()
        if (arm.locateSeq > 0) locator.locate(arm.locateSeq)
        onArmConsumed()
    }

    // 系统返回键：**先关最上面那层覆盖层，全关完了才回会话列表**。
    // 不分层的话「打开大图 → 按返回 → 连会话都退了」，用户还得重新滚回刚才的位置。
    // 层序在 ChatOverlays.Layer（= 渲染顺序），有测试钉着。
    // 被详情页盖住时让位：返回键归盖在上面那一页（它的 BackHandler 注册得更晚本就优先，这里再关一道保险）
    BackHandler(enabled = !covered) {
        val open = buildSet {
            // 记录详情里点开的查看器与聊天页的查看器同一层（两者不会同时开：记录页盖住了聊天页）
            if (viewing != null || recordNav.media != null) add(ChatOverlays.Layer.Viewer)
            if (openUser != null) add(ChatOverlays.Layer.UserProfile)
            if (recordNav.isOpen) add(ChatOverlays.Layer.ChatRecord)
            if (pickingFriend != null) add(ChatOverlays.Layer.FriendPicker)
            if (picking) add(ChatOverlays.Layer.MediaPicker)
            if (pickingFavorites) add(ChatOverlays.Layer.FavoritePicker)
            if (forwarding != null) add(ChatOverlays.Layer.Forward)
            if (menuFor != null) add(ChatOverlays.Layer.ContextMenu)
        }
        when (ChatOverlays.topmost(open)) {
            ChatOverlays.Layer.Viewer -> if (recordNav.media != null) recordNav.closeViewer() else viewing = null
            ChatOverlays.Layer.UserProfile -> openUser = null
            ChatOverlays.Layer.ChatRecord -> recordNav.pop()
            ChatOverlays.Layer.FriendPicker -> pickingFriend = null
            ChatOverlays.Layer.MediaPicker -> picking = false
            ChatOverlays.Layer.FavoritePicker -> pickingFavorites = false
            ChatOverlays.Layer.Forward -> forwarding = null
            ChatOverlays.Layer.ContextMenu -> menuFor = null
            // 没有覆盖层时：**多选态 → 搜索态 → 离开会话**，一层一层退。
            // 多选排在最前：它盖掉了输入栏与标题栏，用户按返回想退的必然是它
            //（不拦的话「进会话 → 按返回 → App 没了」，这是 Android 用户最直觉的一个动作）。
            null -> when {
                selActions.askingMode -> selActions.askingMode = false
                sel.active -> sel.cancel()
                search.open -> search.close()
                else -> onBack()
            }
        }
    }
    val mediaSend = remember(conv.convId) { MediaSendFlow(context, client, conv) }
    // 相机与系统文件选择器这两条「出 App 再回来」的路（含相机产物的跨进程落点），见 ChatMediaLaunchers.kt
    val launchers = rememberChatMediaLaunchers(conv.convId, scope, mediaSend) { toast = it }
    /** 长按菜单锚点：被长按气泡在窗口坐标系里的矩形，菜单按它定位（对齐 iOS UIContextMenu）。 */
    var menuAnchor by remember(conv.convId) { mutableStateOf(Rect.Zero) }

    // initial = null：区分「还没读到」与「读到了、就是空的」——首屏定位要等两路都到（ChatScreen.rowsReady）。
    // 换窗时不会退回 null：collectAsState 的值不随换掉的 flow 重置，新一窗到之前保留旧那一窗。
    val loadedMessages by remember(owner, conv.convId, window) {
        client.repo.observeWindow(owner, conv.convId, window)
    }.collectAsState(initial = null)
    val loadedPending by remember(owner, conv.convId) {
        client.repo.observePending(owner, conv.convId)
    }.collectAsState(initial = null)
    val messages = loadedMessages.orEmpty()
    val pending = loadedPending.orEmpty()
    val rowsReady = loadedMessages != null && loadedPending != null
    com.libeyond.imandroid.ui.voice.VoiceRelayEffect(conv.convId, messages, owner) // 接力连播（语音 §6.4）
    com.libeyond.imandroid.ui.voice.PauseVoiceOnLeave()
    com.libeyond.imandroid.ui.voice.PauseRecordingOnLeave() // 离开聊天页即中断录音（设计 §5.4）

    // **进会话那一刻的快照，之后不再跟随**。
    //
    // 直接用实时的 conv.readSeq / conv.unread 会让未读分割线在进会话后当场消失：
    // 「可见即读」一上报就把 unread 清零，重组时 buildChatRows 拿到 unread=0，
    // 分割线随之不见——用户根本来不及看到自己从哪里开始没读。
    // iOS/Web 同样是冻结入会话快照，不是实时值。
    val entry = remember(conv.convId) { conv.readSeq to conv.unread }

    val rows = remember(messages, pending, entry) {
        buildChatRows(messages, pending, entry.first, entry.second)
    }

    // 副标题（在线态 / 正在输入）连同它的心跳与 watch 订阅，见 ChatPresence.kt
    val subtitle = rememberChatSubtitle(client, conv)

    var lastTypingSent by remember(conv.convId) { mutableStateOf(0L) }
    var replyTo by remember(conv.convId) { mutableStateOf<MessageEntity?>(null) }

    // 群里我是不是管理员——决定「为所有人删除」给不给。
    //
    // 只影响**菜单显不显**，服务端仍会独立校验（越权回 300006）。
    // 即便这里判错也不会越权，最坏是多显/少显一个菜单项——所以不必为它阻塞首屏，
    // 拉不到就按 false 走。
    var iAmManager by remember(conv.convId) { mutableStateOf(false) }
    /** 我在本群的角色。**@所有人 只对群主/管理员出入口**（越权服务端回 300204）。 */
    var myRole by remember(conv.convId) { mutableStateOf<String?>(null) }
    /** 本群成员表（显示名→uid）——只给没有 mention_spans 的老消息兜底，有 uid 才可点（对齐 iOS）。 */
    var mentionNames by remember(conv.convId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** 成员角色与显示名（uid 为键）：发送者徽标、名字、引用块与回复条的名字用。超级群只有我自己。 */
    var memberRoles by remember(conv.convId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 成员表过期（会话开着时对方改名再发消息）→ 版本号 +1 → 下面重拉群资料（MemberNameRefresh.kt）
    val membersRev = rememberMembersRefreshRev(conv.convId, conv.isGroup, rowsReady, messages.lastOrNull(), memberNames)
    LaunchedEffect(conv.convId, membersRev) {
        if (conv.isGroup) {
            runCatchingCancellable { client.groups.info(conv.convId) }
                .onSuccess {
                    iAmManager = it.iAmManager
                    myRole = it.myRole
                    // 超级群这里只回我自己（服务端刻意不下发 2 万人的成员表），
                    // 于是老消息的 @ 在超级群里不高亮——协议里写明的降级，别在这补救
                    mentionNames = it.members.associate { m -> m.displayName to m.userId }
                    memberRoles = it.members.associate { m -> m.userId to m.role }
                    memberNames = it.members.associate { m -> m.userId to m.displayName }
                    memberAvatars = it.members.associate { m -> m.userId to m.avatarUrl }
                }
        }
    }

    // 本窗每个发送者最新一条的昵称快照：成员表查不到时（超级群只有自己）压过那条自己的老快照（SenderNames）。
    val latestNicks = remember(messages) { SenderNames.latestNicknames(messages) }

    // 群 @提及态（M4-8）。**声明在 myRole 之后**——面板要用它决定画不画「@所有人」。
    // 单聊 isGroup=false，面板恒不出现。
    val mention = rememberMentionComposer(client, conv.convId, conv.isGroup, myRole)

    // 老消息补种缩略：原图已在本地（门控判定 Ready）时自己算一张存起来，
    // **下次进这个会话就有磨砂占位了**。挂在「消息列表 + 下载状态」上——
    // 刚下完的那一张正好在这一轮被补上。
    val downloadStates by client.downloads.states.collectAsState()
    LaunchedEffect(messages.size, downloadStates.size, owner) {
        if (owner.isNotEmpty()) {
            runCatchingCancellable { client.thumbBackfill.run(owner, conv.convId, messages) }
        }
    }

    // 长按预览用的渲染参数：**必须与传给 ChatScreen 的那份一致**
    // （ChatScreen 自己也用 ChatRowStyle 组一份，字段来源相同）。
    val rowStyle = ChatRowStyle(
        myUid = owner,
        isGroup = conv.isGroup,
        host = client.host,
        useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
        peerReadSeq = if (conv.isGroup) 0 else conv.peerReadSeq,
        uploadProgress = uploadProgress,
        localNameOf = { uid -> friendsByUid[uid]?.let { DisplayName.ofFriend(it) } },
        remarkOf = { uid -> friendsByUid[uid]?.remark?.takeIf { it.isNotBlank() } },
        latestNicknameOf = { uid -> latestNicks[uid] },
        loadLinkPreview = { url -> client.conversationsApi.linkPreview(url) },
        mentionNames = mentionNames,
        searchHighlight = search.needle,
        roleOf = { uid -> memberRoles[uid] },
        memberNameOf = { uid -> memberNames[uid] },
    )

    // 被盖住时不画、不进无障碍树：它还在组合里（为了返回保位），但读屏不该念出一页看不见的聊天。
    // **只包 ChatScreen**：吐司等浮层若也在这个 Box 里，会跟着隐形、计时却照走——提示就丢了
    Box(Modifier.fillMaxSize().then(if (covered) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)) {
    ChatScreen(
        convId = conv.convId,
        title = Forward.titleOf(conv) { uid -> friendsByUid[uid]?.let { DisplayName.ofFriend(it) } },
        avatarUrl = conv.avatarUrl,
        avatarSeed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
        myUid = owner,
        rows = rows,
        readSeq = entry.first,
        unread = entry.second,
        subtitle = subtitle,
        isGroup = conv.isGroup,
        peerReadSeq = if (conv.isGroup) 0 else conv.peerReadSeq,
        input = input,
        onInputChange = {
            // 系统把 URI 型剪贴项 coerce 成文本插进来：把图摘走，剩下的字回填
            // （没认出图片时 consumeFrom 原样返回，连光标都不动）
            val v = paste.consumeFrom(context, it) { msg -> toast = msg }
            input = v
            mention.onInputChanged(v, conv.isGroup)
        },
        // 粘贴条只负责"挂着、可逐张撤掉"，发送归输入栏那颗发送键（对齐 iOS）
        extraSendable = !paste.isEmpty,
        composerAbove = {
            if (!paste.isEmpty) PasteImageBar(paste)
            if (mention.panelOpen) {
                MentionPanel(
                    members = mention.members,
                    canMentionAll = mention.showsMentionAllRow,
                    onPick = { name, uid -> input = mention.pick(input, name, uid) },
                )
            }
        },
        onTyping = {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent >= TYPING_THROTTLE_MS) {
                lastTypingSent = now
                client.messages.sendTyping(conv.convId)
            }
        },
        onSend = {
            // 粘贴条上挂着的图**随这一次发送一起走**（iOS pasteBar 同）。
            // 先发图再发文字：两者是两条消息，顺序按用户看到的先后来
            if (!paste.isEmpty) {
                val pastedImages = paste.items
                paste.clear()
                scope.launch { mediaSend.send(pastedImages, sendOriginal = false) { toast = it } }
            }
            val text = input.text.trim()
            if (text.isNotEmpty()) {
                val quoted = replyTo
                // 按**文本现状**复核 @ 收件人：点过但又把 token 删掉的人不该收到强提醒
                val at = mention.resolve(text)
                input = TextFieldValue("")
                mention.clear()
                replyTo = null
                // 回到最新不在这里做，收口在 onOutgoingEcho（发图/文件/名片/转发也要回来）
                scope.launch {
                    client.messages.sendText(
                        convId = conv.convId,
                        to = if (conv.isGroup) conv.convId else conv.peerUid,
                        text = text,
                        replyToConvSeq = quoted?.convSeq,
                        mentions = at.mentions,
                        mentionAll = at.mentionAll,
                        mentionSpans = at.spans,
                    )
                }
            }
        },
        onSendVoice = { file, durationMs, waveform ->
            scope.launch { mediaSend.sendVoice(file, durationMs, waveform) }
        },
        onToast = { toast = it },
        onBack = onBack,
        onRetry = { cid -> scope.launch { client.messages.resend(cid) } },
        onVisibleSeq = { seq -> scope.launch { client.messages.markRead(conv.convId, seq) } },
        onOpenInfo = onOpenInfo,

        onAttach = { kind ->
            when (kind) {
                AttachItems.Kind.Photo -> picking = true
                AttachItems.Kind.Camera -> launchers.openCamera()
                AttachItems.Kind.File -> launchers.openFilePicker()
                AttachItems.Kind.ContactCard -> scope.launch {
                    pickingFriend = runCatchingCancellable { client.contacts.friends("accepted") }
                        .getOrElse {
                            toast = Str.s(R.string.chat_attach_friends_load_failed)
                            null
                        }
                }
                // 收藏页的选择模式（iOS `openFavoritesPicker`），发送见下方 ChatPickerLayers 的 onFavoritesPicked
                AttachItems.Kind.Favorite -> pickingFavorites = true
            }
        },
        onLoadOlder = {
            when (val w = window) {
                // 尾窗：只有装满时才继续加——没装满说明本地就这么多，再加只会让同一批数据反复重查
                is ChatWindow.Tail -> if (messages.size >= w.limit) {
                    window = ChatWindow.Tail(w.limit + ChatWindows.TAIL_PAGE)
                }
                // 锚点窗：把下界再往前挪一页。到会话开头时 extendWindowOlder 原样返回，
                // 赋回同一个值不会触发重组（data class 相等），自然停下
                is ChatWindow.Anchored -> scope.launch {
                    window = client.repo.extendWindowOlder(owner, conv.convId, w)
                }
            }
        },
        // 「回到最新」：锚点窗要**换回尾窗**，只滚列表是回不去的（那一窗里根本没有最新那条）
        onJumpToLatest = { com.libeyond.imandroid.sdk.logging.PerfMarks.jumpBottomBegin(conv.convId); window = ChatWindow.Tail(ChatWindows.TAIL_LIMIT) },
        showsJumpToLatest = { away -> ChatWindows.showsJumpToLatest(window, away) },
        onLongPress = { m, rect -> menuFor = m; menuAnchor = rect },
        onOpenMedia = { viewing = it },
        replyTo = replyTo,
        onCancelReply = { replyTo = null },
        loadLinkPreview = { url -> client.conversationsApi.linkPreview(url) },
        host = client.host,
        useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
        uploadProgress = uploadProgress,
        menuForSeq = menuFor?.convSeq ?: 0L,
        // 系统消息里的名字：按本地口径重渲染（备注优先），并可点进资料页。
        // **备注只在这里出现**——分段里的 text 恒为公开昵称，全群共享（IMServer docs/UI.md 隐私红线）。
        localNameOf = { uid -> friendsByUid[uid]?.let { DisplayName.ofFriend(it) } },
        onOpenUser = { uid -> openUser = uid },
        // 定位（引用块跳转 / 搜索命中）统一走 locator：目标常在渲染窗口之外，
        // 只滚列表必然落空。跳不了时它自己会如实说一句。
        locateSeq = locator.target,
        onLocateConsumed = { locator.consumed() },
        onJumpToSeq = { seq -> locator.locate(seq) },
        searchOpen = search.open,
        searchQuery = search.query,
        onSearchQueryChange = { search.setQuery(it) },
        onCloseSearch = { search.close() },
        searchNavLabel = search.navLabel,
        searchNotice = search.notice,
        searchCanPrev = search.canPrev,
        searchCanNext = search.canNext,
        onSearchPrev = { search.goto(search.hitIdx - 1) },
        onSearchNext = { search.goto(search.hitIdx + 1) },
        searchFromLabel = search.fromName.takeIf { search.fromUid != null },
        onOpenSearchFrom = { search.openFromPicker() },
        onClearSearchFrom = { search.clearFrom() },
        onOpenSearchCalendar = { calendar.show() },
        searchFromPickerOpen = search.fromPickerOpen,
        searchFromCandidates = searchFromCandidates,
        onPickSearchFrom = { cand -> search.setFrom(cand.uid, cand.name) },
        selection = sel.selected,
        onToggleSelect = { m -> sel.toggle(m)?.let { toast = it } },
        onCancelSelection = { sel.cancel() },
        onForwardSelected = { selActions.forward() },
        onReportSelected = { selActions.report() },
        onReportBlocked = { selActions.reportBlocked() },
        onFavoriteSelected = { selActions.favorite() },
        onDeleteSelected = { sel.confirmDelete = true },
        mentionNames = mentionNames,
        roleOf = { uid -> memberRoles[uid] },
        memberNameOf = { uid -> memberNames[uid] },
        remarkOf = { uid -> friendsByUid[uid]?.remark?.takeIf { it.isNotBlank() } },
        latestNicknameOf = { uid -> latestNicks[uid] },
        onOpenRecord = { recordNav.push(it) },
        // 点通话记录回拨：与详情页「语音 / 视频」pill 同一入口（RtcCall.placeSingle），忙线 / 权限全由 Kit 守门，宿主不判
        onCallBack = { video -> RtcCall.placeSingle(conv.peerUid, video)?.let { toast = it } },
        searchHighlight = search.needle,
        rowsReady = rowsReady,
        // **自己发消息必须回到最新**：停在历史时发出去的那条在锚点窗里看不见，用户会以为没发出去。
        // 收口在「出箱回显」这一个入口（im-web 2026-09-05 同一条）——此前只挂在发文本上，
        // 发图/文件/名片停在历史时都不回来。已在尾窗就不动，免得把翻出来的更早几页收回去（iOS 同）。
        onOutgoingEcho = { if (window !is ChatWindow.Tail) window = ChatWindow.Tail(ChatWindows.TAIL_LIMIT) },
        covered = covered,
    )
    }

    BatchDeleteConfirm(sel, client, conv.convId, conv.isGroup, iAmManager) { toast = it }

    // —— 聊天记录详情（点合并转发卡进来）。画在查看器与资料页之前：从记录里点名片进的资料页要盖在它上面 ——
    ChatRecordLayer(
        nav = recordNav,
        host = client.host,
        useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
        onOpenUser = { uid -> openUser = uid },
        onSave = saveMedia,
    )

    // —— 媒体查看器（盖在最上层：它比转发/选图更"临时"，用户按返回就该先关它）——
    // 「更多」「媒体」与删除选择单都在 ChatViewerLayer 里；外部动作一律先关查看器再执行
    ChatViewerLayer(
        client = client, conv = conv, viewing = viewing, iAmManager = iAmManager,
        onSave = saveMedia,
        onLocate = { seq -> locator.locate(seq) },
        onForward = { m -> selActions.forwardOne(m) },
        onOpenGallery = onOpenMediaGallery,
        onClose = { viewing = null },
        onToast = { toast = it },
    )

    // 📅 日历跳转弹层：系统 Dialog 自带返回键处理，不必接进 ChatOverlays 的分层返回键
    if (calendar.open) {
        ChatCalendarDialog(
            onDismiss = { calendar.dismiss() },
            onPickDay = { calendar.pick(it) },
            onEarliest = { calendar.earliest() },
            onToday = { calendar.today() },
            activeDays = calendar.activeDays,
        )
    }

    // —— 三层「整页盖住聊天页」的覆盖层：用户资料 / 选联系人发名片 / 相册选择页 ——
    // 渲染顺序即层级，实现在 ChatPickerLayers.kt（返回键仍由上面那个 BackHandler 一处派发）
    ChatPickerLayers(
        client = client,
        openUser = openUser,
        friendsByUid = friendsByUid,
        memberNames = memberNames,
        memberAvatars = memberAvatars,
        onOpenChat = onOpenChat,
        pickingFriend = pickingFriend,
        picking = picking,
        onCloseUser = { openUser = null },
        onCancelFriendPicker = { pickingFriend = null },
        onPickFriend = { f ->
            pickingFriend = null
            scope.launch { mediaSend.sendContactCard(f) { toast = it } }
        },
        onPicked = { items, sendOriginal ->
            picking = false
            scope.launch { mediaSend.send(items, sendOriginal) { toast = it } }
        },
        onDismissPicker = { picking = false },
        pickingFavorites = pickingFavorites,
        onCancelFavorites = { pickingFavorites = false },
        onFavoritesPicked = { msgs ->
            pickingFavorites = false
            // 挂宿主作用域：选择页先关掉自己，挂在它身上的协程会当场被取消
            scope.launch { toast = sendFavoritesTo(client, msgs, conv).ifEmpty { null } }
        },
        onToast = { toast = it },
    )

    // —— 转发目标选择页（覆盖在聊天页之上）——
    val fwd = forwarding
    if (fwd != null) {
        ForwardPickerLayer(client, fwd, sel, scope, onClose = { forwarding = null }, onToast = { toast = it })
        return
    }

    SelectionActionLayers(selActions)

    toast?.let { t ->
        IMToast(t) { toast = null }
    }

    // —— 消息长按菜单 ——（拼装与原位重绘都在 MessageMenuItems.kt）
    menuFor?.let { target ->
        ChatMessageMenu(
            onToast = { toast = it },
            target = target,
            anchor = menuAnchor,
            rows = rows,
            rowStyle = rowStyle,
            client = client,
            conv = conv,
            iAmManager = iAmManager,
            // 复制图片 / 仅删除自己都是 launch 出去的活，**不能挂在菜单自己身上**
            // （菜单点完就关，作用域随之取消）——见 ChatMessageMenu 的 scope 注释
            scope = scope,
            onReply = { replyTo = it },
            onForward = { selActions.forwardOne(it) },
            // 进多选默认勾上触发的那条（同 iOS enterSelectionWithMessage:）
            onMultiSelect = { m -> sel.enter(m) },
            onDismiss = { menuFor = null },
        )
    }
}
