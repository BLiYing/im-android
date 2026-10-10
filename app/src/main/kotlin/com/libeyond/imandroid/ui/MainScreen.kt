package com.libeyond.imandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.blockPointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.BellOff
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.PerfMarks
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.ConversationAction
import com.libeyond.imandroid.data.SettingsRoute
import com.libeyond.imandroid.data.ConversationActions
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.MuteState
import com.libeyond.imandroid.data.NotificationRoute
import com.libeyond.imandroid.data.PushNav
import com.libeyond.imandroid.data.TabUnread
import com.libeyond.imandroid.ui.components.InAppBannerHost
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.MuteDurationSheet
import com.libeyond.imandroid.ui.components.PushBase
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.components.ProvideMenuSurface
import com.libeyond.imandroid.ui.components.menuBackdrop
import com.libeyond.imandroid.ui.components.rememberMenuSurface
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.UnreadCapsule
import com.libeyond.imandroid.ui.components.rememberMuteTick
import kotlinx.coroutines.launch
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.flow.emptyFlow

/** 底部三个 Tab，与 iOS 的 TabBar / Web 的左栏切换同构。 */
private enum class Tab(@StringRes val labelRes: Int) {
    Chats(R.string.ios_tab_messages),
    Contacts(R.string.ios_tab_contacts),
    Me(R.string.ios_tab_me),
}

/**
 * 主界面外壳：底部 Tab + 各 Tab 内容 + 二级页（聊天/找人/新的朋友/资料）。
 *
 * 从 AppRoot 拆出来（CODING_STYLE §7②）：AppRoot 只管「恢复会话 → 登录页 / 主界面」
 * 这一层阶段路由，主界面自己的导航不该混在里面。
 */
@Composable
fun MainScreen(client: IMClient, onLogout: () -> Unit) {
    val owner = client.uid.orEmpty()
    // rememberSaveable：切语言 / 旋转 / 深浅色会重建 Activity，重建后停在原 tab（原先 remember 一律回消息页）
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Tab.Chats) }
    /** 首页搜索点中的设置项落点：切到「我」tab 时一次性交给 [MeHost]（它随 tab 切走而离开组合，状态只能挂这里）。 */
    var settingsRoute by remember { mutableStateOf<SettingsRoute?>(null) }
    // 本地好友表：uid → 整行。资料页进页即用它定型，避免闪动。
    // **存整行不只存 status**：群成员资料页还要拿 remark 当种子，
    // 否则给好友起过备注时，标题会先显昵称、拉到名片后再跳成备注——同一类闪动。
    var knownFriends by remember(owner) { mutableStateOf<Map<String, FriendEntry>>(emptyMap()) }
    LaunchedEffect(owner) {
        if (owner.isNotEmpty()) {
            runCatching { client.contacts.friends() }
                .onSuccess { list -> knownFriends = list.associateBy { it.userId } }
            // 好友事件（对方同意 / 拒绝 / 申请）到来就重拉：只在启动时拉一次的话，之后新加的好友
            // 不在表里，资料页会先当陌生人（2026-10-10）。本机自己同意 / 删除走 onFriendsRefreshed 回灌。
            client.friendEvents.collect {
                runCatchingCancellable { client.contacts.friends() }
                    .onSuccess { list -> knownFriends = list.associateBy { it.userId } }
            }
        }
    }

    var openConv by remember { mutableStateOf<ConversationEntity?>(null) }
    // 通知判定 alertDecision 的 viewingConv 输入（NOTIFICATIONS_DESIGN §3.1）：`openConv` 是
    // "当前打开的会话"的唯一权威来源，写进 data/ 层的全局标记供 MessageService 的 NEW_MSG 分支读——
    // 那条路径没有 Compose 上下文，够不到这个局部变量。
    // 同时：应用内横幅正显示的恰是刚打开的这个会话就收起它（NOTIFICATIONS_P1_DESIGN §1.2「进入该会话」）——
    // 不只是横幅自己被点开这一条路径，群资料里点成员「发消息」这类别的入口进同一会话也该收。
    LaunchedEffect(openConv) {
        com.libeyond.imandroid.data.ViewingConv.current = openConv?.convId
        openConv?.let { PerfMarks.chatOpen(it.convId) }
        openConv?.let { com.libeyond.imandroid.data.InAppBannerStore.dismissIfShowing(it.convId) }
    }

    // 初值 null = 本地库还没回第一份。**不能拿 emptyList() 当初值**：那等于先宣布「还没有会话」、
    // 库回数据再改口——冷启动 / 登录都先闪一下空态（2026-09-15 用户报，判据见 ConversationListPhase）
    val conversations by remember(owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeConversations(owner)
    }.collectAsState(initial = null)
    LaunchedEffect(conversations != null) { if (conversations != null) PerfMarks.conversationListVisible() }
    // 应用内横幅点击按 convId 查会话：O(1) 查表，同 `Forward.kt#targetsInOrder`/`FavoritesHost`/
    // `CallHistoryHost` 既有的 `associateBy { it.convId }` 手法，别在导航这条热路径上现扫一遍全表
    // （`/code-review` 抓出的效率点）。
    val conversationsById = remember(conversations) { conversations.orEmpty().associateBy { it.convId } }

    // 点系统推送通知跳转到会话（M5 批次 2）：MainActivity 收到 intent 后记在 NotificationRoute，
    // 这里在主界面真正组合出来后消费——本地已有这个会话就直接用，没有就用 payload 带的标题现造一个
    // 占位会话（同 QrRouteHost/GroupInfoHost 等处 conversationStubFor 的既有手法），真实数据到了
    // 自然替换。owner 还没就绪（未登录）时不消费，留给账号就绪后这段 LaunchedEffect 因 owner 变化
    // 自然重跑——不需要另起定时器重试，见 NotificationRoute 类注释。
    val pendingNotificationRoute by NotificationRoute.pending.collectAsState()
    LaunchedEffect(pendingNotificationRoute, conversationsById, owner) {
        val target = pendingNotificationRoute ?: return@LaunchedEffect
        if (owner.isEmpty()) return@LaunchedEffect
        val conv = conversationsById[target.convId] ?: when (val kind = NotificationRoute.resolveKind(target.convId, owner)) {
            is NotificationRoute.Kind.Private -> client.conversationStubFor(kind.peerUid, target.title, "")
            NotificationRoute.Kind.Group -> client.groupConversationStubFor(target.convId, target.title, "")
        }
        openConv = conv
        NotificationRoute.consume(target.token)
    }

    val connState by client.socket.state.collectAsState()
    // 底栏「消息」蓝点：与会话行同一份数据现算，口径见 TabUnread（三端同口径）。
    // includeMuted 来自「通知与提示音 ▸ 角标计数」（NOTIFICATIONS_DESIGN §3.4），默认关=现行口径。
    val notifSettings by com.libeyond.imandroid.data.NotificationSettingsStore.settings.collectAsState()
    // 定时免打扰到期刷新（NOTIFICATIONS_P1_DESIGN §4.4）：到点后铃铛/未读徽标/页签角标跟着重组，
    // 不需要服务端推帧。ChatsHost 的会话列表也复用这同一份 tick（往下传），不在那边另起一份定时器。
    val muteTick = rememberMuteTick(conversations)
    val tabUnread = remember(conversations, notifSettings.badge.includeMuted, muteTick) {
        TabUnread.count(conversations.orEmpty(), notifSettings.badge.includeMuted, muteTick)
    }

    // 进主界面就拉一次会话列表——WS 的 onConnected 也会拉，但那条路只在
    // 「本次冷启动真的新建了连接」时触发；会话已存活时进来不会有 onConnected。
    LaunchedEffect(owner) {
        if (owner.isNotEmpty()) {
            // **账号就绪**才跑的一次性数据订正。放这儿不放 onConnected：WS 常常先连上、
            // 会话才恢复，那时 owner 还是空的、那个回调整个早退（2026-09-09 实测
            // `ws_connected {uid=-}`）。它自己带一次性标记，重复调用是廉价空转。
            client.convergeLegacyDataOnce()
            client.messages.refreshConversations()
        }
    }

    // —— 二级页：聊天 / 群资料（占满全屏，不显 Tab 栏）——
    var infoForConv by remember { mutableStateOf<ConversationEntity?>(null) }
    /** 详情页进来先落在哪个页签；null = 各自默认（群资料「成员」、单聊「媒体」）。查看器的「媒体」钮要直达媒体页签。 */
    var infoTab by remember { mutableStateOf<com.libeyond.imandroid.data.DetailTab?>(null) }
    /**
     * 这一趟进的是**会话媒体库**（查看器右下角「媒体」钮），不是「聊天信息」。
     * iOS 那颗钮打开的是独立的 `IMConversationMediaViewController`；本端此前跳详情页并落在
     * 媒体页签上——用户点「媒体」却进了设置页（2026-09-16 用户报）。页面复用同一个宿主，
     * 只是把头部与页签条收起来（`galleryOnly`），归档取数/长按菜单/查看器那整套接线不另写一份。
     */
    var infoGallery by remember { mutableStateOf(false) }
    var infoApproval by remember { mutableStateOf(false) }
    /**
     * 「关掉详情页，回聊天页顺带做一件事」的待办（开搜索 / 定位到某条）。
     * 为什么要绕这一道、为什么两件事合成一个类型，见 [ChatArm]。
     */
    var chatArm by remember { mutableStateOf(ChatArm()) }
    // 回到会话列表 = 没有聊天页能接待办了：清掉，免得一件没送达的（如资料页「搜索」换会话没成）
    // 留到以后某次再进那个会话时突然开出搜索态
    LaunchedEffect(openConv == null) { if (openConv == null) chatArm = ChatArm() }

    // 会话列表滚动位置：进聊天页时 Tab 层整个离开组合，放在 ChatsHost/列表里会随之丢失（返回回顶部）
    val chatsListState = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        androidx.compose.foundation.lazy.LazyListState()
    }
    var menuFor by remember { mutableStateOf<ConversationEntity?>(null) }
    val tabMenuSurface = rememberMenuSurface()
    var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // 会话列表左滑/长按「免打扰」的时长菜单（NOTIFICATIONS_P1_DESIGN §4.1/§4.2）：非空 = 敞开着，
    // 叠在最外层（同 InAppBannerHost 这一层），不是 ConversationMenu 自己的子状态——两个弹层不能
    // 同时占用同一份 anchor 坐标系（时长菜单是居中的底部弹层，不用贴着长按的那一行）。
    var muteSheetFor by remember { mutableStateOf<ConversationEntity?>(null) }
    val scope = rememberCoroutineScope()

    // 底部 Tab 栏由各 Tab 的**根页**自己画（[TabRoot]），二级页整屏铺满——判据 PushNav.showsTabBar。
    // 此前底栏画在这一层、各 Tab 的二级页在它上面的内容区里原地切换，于是一直挂着（2026-09-15 用户报）
    // 通讯录 Tab 角标 = 待我确认的好友申请数。ContactsHost 只在该 Tab 内才在组合里，
    // 所以这里自己持有计数：冷启动 / 好友帧先在此处拉一次（rememberContactsPending），
    // 进了通讯录再由 ContactsHost 的最新值覆盖（同意 / 拒绝后立刻减）。
    var contactsPending by rememberContactsPending(client, owner)
    val bottomBar: @Composable () -> Unit = {
        BottomBar(current = tab, unread = tabUnread, contactsPending = contactsPending, onSelect = { tab = it })
    }

    // 应用内横幅（NOTIFICATIONS_P1_DESIGN §1.2）挂在这一层最上面——不是更外层的 AppRoot：
    // 点横幅要"以点会话列表行同一路径"进会话，那条路径就是下面这个 `openConv = it`，
    // 是本函数的私有导航状态，AppRoot 那一层够不着（与 QrRouteHost 挂在这里的理由同构）。
    /**
     * 换会话的公共收口。**同一会话不换实体**：资料页「搜索」pill 可能指向正开着的这个单聊
     * （单聊详情里点对方名片 → 搜索），此时 openConv 已是带未读 / 已读位点的真实会话，
     * 换成资料页手里那份桩（stub）只会丢字段；待办由 chatArm 送达，用不着换页。
     */
    val switchTo: (ConversationEntity) -> Unit = { c -> if (openConv?.convId != c.convId) openConv = c }
    androidx.compose.runtime.CompositionLocalProvider(LocalChatArmSink provides { chatArm = it }) {
    Box(Modifier.fillMaxSize()) {
        // 扫码/点链接加群路由宿主：挂在这里（不是更外层的 AppRoot）是因为它要改 openConv 来进群聊/单聊，
        // 那份状态是本函数的私有变量，宿主离它太远够不着（QrRouteHost.kt 头注释）。
        QrRouteHost(client = client, onOpenChat = switchTo) {
            // —— 一级 push：Tab 根 ↔ 聊天页 ——
            // 进聊天页时整个 Tab 层（连同底栏）向左让开、转场结束后离开组合——与此前 early return 同一语义
            PushTransition(
                targetState = openConv,
                depthOf = { if (it == null) PushNav.ROOT_DEPTH else 1 },
                // 同一个会话的实体被刷新不算换页；换会话（群资料里点成员「发消息」）才转场
                contentKey = { it?.convId },
            ) { conv ->
                if (conv == null) {
                    Box(Modifier.fillMaxSize().background(IMTheme.colors.groupedBackground)) {
                        // 长按会话：整页模糊 + 那一行原位浮起（iOS 会话列表的 UIContextMenu）；菜单画在被模糊的这层之外
                        ProvideMenuSurface(tabMenuSurface) {
                        Box(Modifier.fillMaxSize().menuBackdrop(tabMenuSurface.backdrop)) {
                        when (tab) {
                            Tab.Chats -> ChatsHost(
                                client = client,
                                conversations = conversations,
                                conn = connState,
                                knownFriends = knownFriends,
                                onOpenChat = { openConv = it },
                                onOpenChatAt = { c, seq -> chatArm = ChatArm(locateSeq = seq); openConv = c },
                                onLongPress = { c, rect -> menuFor = c; menuAnchor = rect },
                                onOpenSetting = { settingsRoute = it; tab = Tab.Me },
                                bottomBar = bottomBar,
                                muteNow = muteTick,
                                listState = chatsListState,
                            )
                            Tab.Contacts -> ContactsHost(
                                client = client, onOpenChat = { openConv = it }, bottomBar = bottomBar,
                                onPendingCount = { contactsPending = it },
                                onFriendsLoaded = { l -> knownFriends = l.associateBy { it.userId } },
                            )
                            Tab.Me -> MeHost(
                                client = client, onLogout = onLogout, bottomBar = bottomBar,
                                onOpenChat = { openConv = it },
                                initialRoute = settingsRoute,
                                onRouteConsumed = { settingsRoute = null },
                            )
                        }
                        }
                        menuFor?.let { target ->
                            ConversationMenu(
                                client, target, menuAnchor, scope, muteTick,
                                onRequestMuteSheet = { conv -> menuFor = null; muteSheetFor = conv },
                                onDismiss = { menuFor = null },
                            )
                        }
                        }
                    }
                } else {
                    val covered = infoForConv != null
                    Box(Modifier.fillMaxSize()) {
                        // 详情页**盖在**聊天页之上，聊天页不出组合（iOS push 之后底下那个 VC 还活着，同构）。
                        // 此前是二选一的 `return`：进详情就把 ChatHost 整个移出组合，回来时列表状态从头建、
                        // 按首屏规则重新定位——停在历史里点进详情，回来被甩回首条未读或底部（设计稿 #10）。
                        // 被盖住时只左移让开（PushBase），不离开组合
                        PushBase(covered = covered) {
                            // 换会话（群资料里点成员「发消息」）要整页重建：列表位置、输入框、覆盖层都是按会话的
                            key(conv.convId) {
                                ChatHost(
                                    client = client,
                                    conv = conv,
                                    onBack = { openConv = null },
                                    onOpenInfo = { infoTab = null; infoGallery = false; infoApproval = false; infoForConv = conv },
                                    onOpenApproval = { infoTab = null; infoGallery = false; infoApproval = true; infoForConv = conv },
                                    onOpenMediaGallery = {
                                        infoTab = com.libeyond.imandroid.data.DetailTab.Media
                                        infoGallery = true
                                        infoForConv = conv
                                    },
                                    // 只交归这一页的待办：换会话转场期间旧页还在组合里（见 ChatArm.convId）
                                    arm = chatArm.forConv(conv.convId),
                                    onArmConsumed = { chatArm = ChatArm() },
                                    covered = covered,
                                    onOpenChat = switchTo,
                                    // 返回钮红圈 = 其它会话的未读总数（iOS totalUnreadExcludingConv）；按 convId 排除，不含本会话
                                    backUnread = conversations.orEmpty().filter { it.convId != conv.convId }.sumOf { it.unread },
                                )
                            }
                        }
                        // —— 二级 push：聊天页 → 会话详情 / 群资料 ——
                        PushTransition(
                            targetState = infoForConv,
                            depthOf = { if (it == null) 0 else 1 },
                            contentKey = { it?.convId },
                        ) { info ->
                            if (info != null) {
                                Box(Modifier.fillMaxSize().blockPointerInput()) {
                                    InfoPage(client, info, knownFriends, { l -> knownFriends = l.associateBy { it.userId } }, infoTab, infoGallery, infoApproval,
                                        onOpenChat = { stub -> infoForConv = null; switchTo(stub) },
                                        onArm = { arm -> infoForConv = null; chatArm = arm },
                                        onBack = { infoForConv = null },
                                        onLeft = { infoForConv = null; openConv = null },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // 提示条是铺满全屏的 Box，必须画在内容之后（最上层），否则被盖住看不见
        GroupEventsEffect(client, openConv?.convId) { infoForConv = null; openConv = null }
        InAppBannerHost(
            onOpen = { convId ->
                val conv = conversationsById[convId]
                if (conv != null) openConv = conv
                conv != null
            },
        )
        muteSheetFor?.let { conv ->
            MuteDurationSheet(
                convTitle = Forward.titleOf(conv),
                // 会话列表这条入口不给「取消免打扰」项：已免打扰时 ConversationActions 给的是
                // Unmute 项，点了直接调 settings(muted=false)，走不到这个弹层（§4.2 入口表）。
                showUnmute = false,
                onUnmute = {},
                onSelect = { d ->
                    scope.launch {
                        runCatching { settings(client, conv, muted = true, muteUntil = d.muteUntil()) }
                        client.messages.refreshConversations()
                    }
                    muteSheetFor = null
                },
                onDismiss = { muteSheetFor = null },
            )
        }
    }
    }
}

/**
 * 会话长按菜单（CHAT_UX §12/§14）。
 *
 * **不是底部弹窗**：iOS 会话列表长按走的是 UIContextMenu（原位、贴着那一行）。
 * 底部弹窗把「操作哪一条」这个信息丢了——手指在屏幕上半部长按，眼睛却要跑到底部找菜单。
 * 与消息长按共用同一个组件，两处交互才一致。
 */
@Composable
private fun ConversationMenu(
    client: IMClient,
    target: ConversationEntity,
    anchor: androidx.compose.ui.geometry.Rect,
    scope: kotlinx.coroutines.CoroutineScope,
    /** 判「是否免打扰」的当前时刻——决定菜单给「免打扰」还是「取消免打扰」（§4.1/§4.2）。 */
    nowMs: Long,
    /** 点了「免打扰」（且当前未免打扰）：交给调用方弹时长菜单，不在这里直接置 muted=true
     *  （§4.1：未免打扰时这一项要先选时长，已免打扰时这一项本身就是「取消免打扰」，直接生效）。 */
    onRequestMuteSheet: (ConversationEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val mutedNow = MuteState.isMutedNow(target.muted, target.muteUntil, nowMs)
    val lift = com.libeyond.imandroid.ui.components.LocalMenuLift.current
    MessageContextMenu(
        anchor = anchor,
        // 会话行是整行全宽的，菜单靠左（跟着行的起始边，与 iOS 的 preview 锚点同侧）
        mine = false,
        preview = lift?.let { { com.libeyond.imandroid.ui.components.LiftPreview(it) } },
        items = ConversationActions
            .availableFor(target.pinnedAt, mutedNow, target.markedUnread, target.unread)
            .map { a ->
                SheetItem(a.label, a.destructive, icon = convActionIcon(a)) {
                    if (a == ConversationAction.Mute) {
                        onRequestMuteSheet(target)
                    } else {
                        scope.launch {
                            runCatching {
                                when (a) {
                                    ConversationAction.Pin -> settings(client, target, pinnedAt = System.currentTimeMillis())
                                    ConversationAction.Unpin -> settings(client, target, pinnedAt = 0)
                                    ConversationAction.Unmute -> settings(client, target, muted = false)
                                    ConversationAction.MarkUnread -> settings(client, target, markedUnread = true)
                                    ConversationAction.MarkRead -> settings(client, target, markedUnread = false)
                                    ConversationAction.Delete -> client.messages.deleteConversation(target.convId)
                                    ConversationAction.Mute -> Unit // 上面已分流，走不到这里
                                }
                            }
                            client.messages.refreshConversations()
                        }
                    }
                }
            },
        onDismiss = onDismiss,
    )
}

/**
 * 会话详情页：群聊进群资料，单聊进「聊天信息」。
 *
 * **单聊进的是「聊天信息」不是「用户资料」**：后者回答"这个人是谁"，
 * 前者回答"这段对话怎么设置"（置顶/免打扰/发过哪些媒体）。
 * 用户资料页现在是它 push 出去的一页，与 iOS `IMChatDetailViewController` 同构。
 */
@Composable
private fun InfoPage(
    client: IMClient,
    conv: ConversationEntity,
    knownFriends: Map<String, FriendEntry>,
    onFriendsRefreshed: (List<FriendEntry>) -> Unit,
    /** 先落在哪个页签；null = 各自默认。 */
    initialTab: com.libeyond.imandroid.data.DetailTab?,
    /** 这一趟只当会话媒体库用（收起头部与页签条）。 */
    galleryOnly: Boolean,
    openJoinRequests: Boolean,
    onOpenChat: (ConversationEntity) -> Unit,
    onArm: (ChatArm) -> Unit,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    if (conv.isGroup) {
        GroupInfoHost(
            client = client,
            convId = conv.convId,
            knownFriends = knownFriends,
            // 成员资料页里点「发消息」：关掉群资料、直接进与该成员的单聊
            onOpenChat = onOpenChat,
            onSearchInChat = { onArm(ChatArm(openSearch = true)) },
            onLocateInChat = { seq -> onArm(ChatArm(locateSeq = seq)) },
            initialTab = initialTab ?: com.libeyond.imandroid.data.DetailTab.Members,
            galleryOnly = galleryOnly,
            seed = com.libeyond.imandroid.sdk.api.GroupInfo(convId = conv.convId, name = conv.title, avatarUrl = conv.avatarUrl),
            openJoinRequests = openJoinRequests,
            onBack = onBack,
            onLeft = onLeft,
        )
    } else {
        ChatDetailHost(
            client = client,
            conv = conv,
            knownFriends = knownFriends,
            onFriendsRefreshed = onFriendsRefreshed,
            onSearchInChat = { onArm(ChatArm(openSearch = true)) },
            onLocateInChat = { seq -> onArm(ChatArm(locateSeq = seq)) },
            // 「名片」页签点开的资料页里「消息」/「搜索」要能换会话——此前没传，走空默认实现，点了没反应
            onOpenChat = onOpenChat,
            initialTab = initialTab ?: com.libeyond.imandroid.data.DetailTab.Media,
            galleryOnly = galleryOnly,
            onBack = onBack,
        )
    }
}

/**
 * 会话菜单项的图标。**逐项对齐 iOS `conversationActionsFor:` 里的 SF Symbol**
 * （pin / pin.slash / bell / bell.slash / checkmark.circle / circle / trash），
 * 用 Lucide 里语义最近的一枚——SF Symbol 在 Android 上不存在，
 * 要对齐的是「每项都有图标且认得出」，不是同一张图（docs/UI_PARITY_IOS.md §3）。
 */
private fun convActionIcon(a: ConversationAction) = when (a) {
    ConversationAction.Pin -> Lucide.Pin
    ConversationAction.Unpin -> Lucide.PinOff
    ConversationAction.Mute -> Lucide.BellOff
    ConversationAction.Unmute -> Lucide.Bell
    ConversationAction.MarkRead -> Lucide.CircleCheck
    ConversationAction.MarkUnread -> Lucide.Circle
    ConversationAction.Delete -> Lucide.Trash2
}

/**
 * 改会话设置。**整体替换三项**（§6.8），所以未指定的项要用当前值填回去——
 * 漏传一项等于把它清零，「置顶一下顺手把免打扰关了」就是这么来的。
 */
private suspend fun settings(
    client: IMClient,
    conv: ConversationEntity,
    pinnedAt: Long = conv.pinnedAt,
    muted: Boolean = conv.muted,
    markedUnread: Boolean = conv.markedUnread,
    /** 定时免打扰到期毫秒——**只有选了时长菜单的调用点才传**，其余调用点留 `null`（省略），
     *  让服务端按 PROTOCOL §6.10 的缺省规则保留原到期时间（不然置顶一下会把定时免打扰变成永久）。 */
    muteUntil: Long? = null,
) {
    client.conversationsApi.updateSettings(conv.convId, pinnedAt, muted, markedUnread, muteUntil)
}

@Composable
private fun BottomBar(current: Tab, unread: Int, contactsPending: Int, onSelect: (Tab) -> Unit) {
    val c = IMTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(
            modifier = Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding(),
        ) {
            Tab.entries.forEach { t ->
                val selected = t == current
                Column(
                    modifier = Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Image(
                            imageVector = when (t) {
                                Tab.Chats -> Lucide.MessageCircle
                                Tab.Contacts -> Lucide.Users
                                Tab.Me -> Lucide.User
                            },
                            contentDescription = stringResource(t.labelRes),
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(if (selected) c.accent else c.textTertiary),
                        )
                        if (t == Tab.Contacts) {
                            // 同入口行的蓝色胶囊（0 隐藏，>99 → 99+）；挂在图标右上角并略外移
                            UnreadCapsule(contactsPending, Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp))
                        }
                        if (t == Tab.Chats && unread > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .background(c.unreadBadge, androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                    Text(
                        text = stringResource(t.labelRes),
                        color = if (selected) c.accent else c.textTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
