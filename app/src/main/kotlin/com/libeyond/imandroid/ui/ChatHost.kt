package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.ui.screens.ChatRowStyle
import com.libeyond.imandroid.ui.screens.ChatRowView
import com.libeyond.imandroid.ui.theme.IMTheme
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.sdk.logging.IMLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.libeyond.imandroid.data.AttachItems
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.screens.FriendPickerScreen
import com.libeyond.imandroid.ui.screens.MediaViewerScreen
import com.libeyond.mediapicker.MediaPickerHost
import com.libeyond.mediapicker.PickedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.data.MessageAction
import com.libeyond.imandroid.data.AlbumLayout
import com.libeyond.imandroid.data.MessageActions
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.Bubble
import com.libeyond.imandroid.ui.components.MessageContextMenu
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import com.libeyond.imandroid.data.ChatWindow
import com.libeyond.imandroid.data.ChatWindows
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.Presence
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.ui.screens.AlbumBubble
import com.libeyond.imandroid.ui.screens.AlbumTile
import com.libeyond.imandroid.ui.screens.ChatRow
import com.libeyond.imandroid.ui.screens.ChatScreen
import com.libeyond.imandroid.ui.screens.buildChatRows
import kotlinx.coroutines.delay
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
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val owner = client.uid.orEmpty()
    var input by remember(conv.convId) { mutableStateOf("") }

    // —— 转发（M4-3）——
    // 待转发的消息列表（null = 没在转发）。选完目标会话后逐条发出。
    var forwarding by remember(conv.convId) { mutableStateOf<List<MessageEntity>?>(null) }
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
    /** 正在全屏查看的媒体（null = 没在看）。 */
    var viewing by remember(conv.convId) { mutableStateOf<MessageEntity?>(null) }
    // 点系统消息里的名字进的资料页
    var openUser by remember(conv.convId) { mutableStateOf<String?>(null) }
    var friendsByUid by remember(conv.convId) { mutableStateOf<Map<String, FriendEntry>>(emptyMap()) }
    LaunchedEffect(conv.convId) {
        runCatching { client.contacts.friends() }.onSuccess { l -> friendsByUid = l.associateBy { it.userId } }
    }
    var menuFor by remember(conv.convId) { mutableStateOf<MessageEntity?>(null) }

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
    BackHandler {
        val open = buildSet {
            if (viewing != null) add(ChatOverlays.Layer.Viewer)
            if (openUser != null) add(ChatOverlays.Layer.UserProfile)
            if (pickingFriend != null) add(ChatOverlays.Layer.FriendPicker)
            if (picking) add(ChatOverlays.Layer.MediaPicker)
            if (forwarding != null) add(ChatOverlays.Layer.Forward)
            if (menuFor != null) add(ChatOverlays.Layer.ContextMenu)
        }
        when (ChatOverlays.topmost(open)) {
            ChatOverlays.Layer.Viewer -> viewing = null
            ChatOverlays.Layer.UserProfile -> openUser = null
            ChatOverlays.Layer.FriendPicker -> pickingFriend = null
            ChatOverlays.Layer.MediaPicker -> picking = false
            ChatOverlays.Layer.Forward -> forwarding = null
            ChatOverlays.Layer.ContextMenu -> menuFor = null
            // 没有覆盖层时：搜索态先退回普通聊天页，再按一次才离开会话
            //（微信/Telegram 同）。不拦的话「进会话 → 按返回 → App 没了」，
            // 这是 Android 用户最直觉的一个动作。
            null -> if (search.open) search.close() else onBack()
        }
    }
    val mediaSend = remember(conv.convId) { MediaSendFlow(context, client, conv) }
    /** 相机产物的落点；拍完从这里读字节。 */
    var cameraUri by remember(conv.convId) { mutableStateOf<android.net.Uri?>(null) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        // ok=false 就是用户在相机里按了取消——**不提示**，那不是错误
        if (ok && uri != null) {
            scope.launch {
                mediaSend.send(
                    listOf(
                        PickedMedia(
                            uri = uri.toString(),
                            displayName = "camera_${System.currentTimeMillis()}.jpg",
                            mime = "image/jpeg",
                            // 相机产物走压缩路径，字节数由压缩后的结果决定，这里给 1 只为过
                            // 「0 = MediaStore 坏行」那道判断
                            sizeBytes = 1,
                            isVideo = false,
                        ),
                    ),
                    // 相机原片动辄 10MB+，默认压（与相册同口径）
                    sendOriginal = false,
                ) { toast = it }
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch { mediaSend.sendFile(uri) { toast = it } }
    }
    /** 长按菜单锚点：被长按气泡在窗口坐标系里的矩形，菜单按它定位（对齐 iOS UIContextMenu）。 */
    var menuAnchor by remember(conv.convId) { mutableStateOf(Rect.Zero) }

    val messages by remember(owner, conv.convId, window) {
        client.repo.observeWindow(owner, conv.convId, window)
    }.collectAsState(initial = emptyList())

    val pending by remember(owner, conv.convId) {
        client.repo.observePending(owner, conv.convId)
    }.collectAsState(initial = emptyList())

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

    // —— 在线态：租约模型要求客户端自己敲心跳重算 ——
    // 「租约到期」是纯粹的时间流逝，不触发任何回调；不主动重算的话，
    // 用户静止不动时副标题会**永远**停在「在线」（PROTOCOL §5.5）。
    val presenceMap by client.presence.presence.collectAsState()
    val typingMap by client.presence.typing.collectAsState()
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(conv.convId) {
        // 进会话即上报 watch。force=true：服务端对每次 watch（含集合不变的重发）都回快照，
        // 返回聊天页正靠它刷新，别因为集合相同就跳过。
        if (!conv.isGroup && conv.peerUid.isNotEmpty()) {
            client.messages.sendWatch(setOf(conv.peerUid), force = true)
        }
        var sinceSnapshot = 0L
        while (true) {
            delay(Presence.TICK_MS)
            tick = System.currentTimeMillis()
            sinceSnapshot += Presence.TICK_MS
            // 对端不在线时定期重拉快照：单聊 topic 随首条消息才建，
            // 「刚加好友但从未聊过」的对端收不到上线帧，只靠帧永远升不回「在线」。
            if (sinceSnapshot >= Presence.SNAPSHOT_REFRESH_MS && !conv.isGroup) {
                sinceSnapshot = 0
                val p = client.presence.snapshotOf(conv.peerUid)
                val d = Presence.display(p.status, p.onlineUntil, p.lastSeen, tick)
                if (Presence.needsSnapshotRefresh(d)) client.messages.refreshConversations()
            }
        }
    }

    // 退出聊天页清空 watch 集（全量替换语义）
    DisposableEffect(conv.convId) {
        onDispose { client.messages.sendWatch(emptySet(), force = true) }
    }

    val subtitle = remember(conv.convId, presenceMap, typingMap, tick) {
        val who = client.presence.typingIn(conv.convId, tick)
        when {
            who != null -> "正在输入…"
            conv.isGroup -> ""
            else -> {
                val p = client.presence.snapshotOf(conv.peerUid)
                Presence.label(Presence.display(p.status, p.onlineUntil, p.lastSeen, tick), tick)
            }
        }
    }

    var lastTypingSent by remember(conv.convId) { mutableStateOf(0L) }
    var replyTo by remember(conv.convId) { mutableStateOf<MessageEntity?>(null) }

    // 群里我是不是管理员——决定「为所有人删除」给不给。
    //
    // 只影响**菜单显不显**，服务端仍会独立校验（越权回 300006）。
    // 即便这里判错也不会越权，最坏是多显/少显一个菜单项——所以不必为它阻塞首屏，
    // 拉不到就按 false 走。
    var iAmManager by remember(conv.convId) { mutableStateOf(false) }
    LaunchedEffect(conv.convId) {
        if (conv.isGroup) {
            runCatching { client.groups.info(conv.convId) }
                .onSuccess { iAmManager = it.iAmManager }
        }
    }

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
        loadLinkPreview = { url -> client.conversationsApi.linkPreview(url) },
        searchHighlight = search.needle,
    )

    Box(Modifier.fillMaxSize()) {
    ChatScreen(
        convId = conv.convId,
        title = conv.title.ifBlank { conv.convId },
        avatarUrl = conv.avatarUrl,
        myUid = owner,
        rows = rows,
        readSeq = entry.first,
        unread = entry.second,
        subtitle = subtitle,
        isGroup = conv.isGroup,
        peerReadSeq = if (conv.isGroup) 0 else conv.peerReadSeq,
        input = input,
        onInputChange = { input = it },
        onTyping = {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent >= TYPING_THROTTLE_MS) {
                lastTypingSent = now
                client.messages.sendTyping(conv.convId)
            }
        },
        onSend = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                val quoted = replyTo
                input = ""
                replyTo = null
                // **自己发消息必须回到最新**：停在历史时发出去的那条在锚点窗里看不见，
                // 用户会以为没发出去（im-web 2026-09-05 修过同一条，收口在"出箱回显唯一入口"上）
                window = ChatWindow.Tail(ChatWindows.TAIL_LIMIT)
                scope.launch {
                    client.messages.sendText(
                        convId = conv.convId,
                        to = if (conv.isGroup) conv.convId else conv.peerUid,
                        text = text,
                        replyToConvSeq = quoted?.convSeq,
                    )
                }
            }
        },
        onBack = onBack,
        onRetry = { cid -> scope.launch { client.messages.resend(cid) } },
        onVisibleSeq = { seq -> scope.launch { client.messages.markRead(conv.convId, seq) } },
        onOpenInfo = onOpenInfo,

        onAttach = { kind ->
            when (kind) {
                AttachItems.Kind.Photo -> picking = true
                AttachItems.Kind.Camera -> {
                    val uri = MediaSendFlow.newCameraUri(context)
                    if (uri == null) {
                        toast = "打不开相机"
                    } else {
                        cameraUri = uri
                        takePhoto.launch(uri)
                    }
                }
                // 任意类型：服务端按扩展名走白名单，端上不预筛——预筛只会让用户
                // 「明明有这个文件却选不中」，而真正的规则在服务端
                AttachItems.Kind.File -> pickFile.launch(arrayOf("*/*"))
                AttachItems.Kind.ContactCard -> scope.launch {
                    pickingFriend = runCatchingCancellable { client.contacts.friends("accepted") }
                        .getOrElse {
                            toast = "联系人加载失败"
                            null
                        }
                }
                // 与 iOS 一致：整个功能三端都没做
                AttachItems.Kind.AudioVideo -> toast = "音视频通话还没做"
                // 本端没有收藏能力（无 API、无页面）
                AttachItems.Kind.Favorite -> toast = "收藏还没做"
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
        onJumpToLatest = { window = ChatWindow.Tail(ChatWindows.TAIL_LIMIT) },
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
        searchHighlight = search.needle,
    )

    // —— 媒体查看器（盖在最上层：它比转发/选图更"临时"，用户按返回就该先关它）——
    viewing?.let { m ->
        MediaViewerScreen(
            contentType = m.contentType,
            content = m.content,
            poster = m.poster.orEmpty(),
            // 门控已经把它下到本地了，查看器就该放本地那份（断网也看得了）
            localFile = client.downloads.localFile(m.content, m.contentType == ContentType.VIDEO),
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            onSave = saveMedia,
            // 先关查看器再开转发选择页：两层叠着关掉上面一层会露出黑底大图
            onForward = { viewing = null; forwarding = listOf(m) },
            onClose = { viewing = null },
        )
    }

    // —— 点系统消息里的名字 → 用户资料页 ——
    openUser?.let { uid ->
        val f = friendsByUid[uid]
        UserProfileHost(
            client = client,
            userId = uid,
            knownRelation = f?.status.orEmpty(),
            seed = com.libeyond.imandroid.sdk.api.UserCard(
                userId = uid,
                username = f?.username.orEmpty(),
                nickname = f?.nickname.orEmpty(),
                avatarUrl = f?.avatarUrl.orEmpty(),
                remark = f?.remark.orEmpty(),
            ),
            onSendMessage = { openUser = null },
            onBack = { openUser = null },
        )
    }

    // —— 选联系人发名片（覆盖在聊天页之上）——
    pickingFriend?.let { list ->
        FriendPickerScreen(
            friends = list,
            onCancel = { pickingFriend = null },
            onPick = { f ->
                pickingFriend = null
                scope.launch { mediaSend.sendContactCard(f) { toast = it } }
            },
        )
    }

    // —— 相册选择页（覆盖在聊天页之上）——
    if (picking) {
        MediaPickerHost(
            skin = rememberPickerSkin(),
            onPicked = { items, sendOriginal ->
                picking = false
                scope.launch { mediaSend.send(items, sendOriginal) { toast = it } }
            },
            onDismiss = { picking = false },
            onToast = { toast = it },
            log = PickerLog,
        )
    }

    // —— 转发目标选择页（覆盖在聊天页之上）——
    val fwd = forwarding
    if (fwd != null) {
        val convs by client.repo.observeConversations(owner).collectAsState(initial = emptyList())
        ForwardPickerScreen(
            conversations = convs,
            count = fwd.size,
            onCancel = { forwarding = null },
            onToast = { toast = it },
            // 串行发送与 forwardFrom 口径收在 forwardMessages 里（详情页归档转发共用同一份）
            onConfirm = { targets ->
                forwarding = null
                scope.launch { toast = forwardMessages(client, fwd, targets) }
            },
        )
        return
    }

    toast?.let { t ->
        IMToast(t) { toast = null }
    }

    // —— 消息长按菜单 ——（拼装与原位重绘都在 MessageMenuItems.kt）
    menuFor?.let { target ->
        ChatMessageMenu(
            target = target,
            anchor = menuAnchor,
            rows = rows,
            rowStyle = rowStyle,
            client = client,
            conv = conv,
            iAmManager = iAmManager,
            onReply = { replyTo = it },
            onForward = { forwarding = listOf(it) },
            onDismiss = { menuFor = null },
        )
    }
    }
}
