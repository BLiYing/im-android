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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
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
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.ConversationAction
import com.libeyond.imandroid.data.ConversationActions
import com.libeyond.imandroid.data.PushNav
import com.libeyond.imandroid.data.TabUnread
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.PushBase
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.components.SheetItem
import kotlinx.coroutines.launch
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.flow.emptyFlow

/** 底部三个 Tab，与 iOS 的 TabBar / Web 的左栏切换同构。 */
private enum class Tab(val label: String) { Chats("消息"), Contacts("通讯录"), Me("我") }

/**
 * 主界面外壳：底部 Tab + 各 Tab 内容 + 二级页（聊天/找人/新的朋友/资料）。
 *
 * 从 AppRoot 拆出来（CODING_STYLE §7②）：AppRoot 只管「恢复会话 → 登录页 / 主界面」
 * 这一层阶段路由，主界面自己的导航不该混在里面。
 */
@Composable
fun MainScreen(client: IMClient, onLogout: () -> Unit) {
    val owner = client.uid.orEmpty()
    var tab by remember { mutableStateOf(Tab.Chats) }
    // 本地好友表：uid → 整行。资料页进页即用它定型，避免闪动。
    // **存整行不只存 status**：群成员资料页还要拿 remark 当种子，
    // 否则给好友起过备注时，标题会先显昵称、拉到名片后再跳成备注——同一类闪动。
    var knownFriends by remember(owner) { mutableStateOf<Map<String, FriendEntry>>(emptyMap()) }
    LaunchedEffect(owner) {
        if (owner.isNotEmpty()) {
            runCatching { client.contacts.friends() }
                .onSuccess { list -> knownFriends = list.associateBy { it.userId } }
        }
    }

    var openConv by remember { mutableStateOf<ConversationEntity?>(null) }

    // 初值 null = 本地库还没回第一份。**不能拿 emptyList() 当初值**：那等于先宣布「还没有会话」、
    // 库回数据再改口——冷启动 / 登录都先闪一下空态（2026-09-15 用户报，判据见 ConversationListPhase）
    val conversations by remember(owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeConversations(owner)
    }.collectAsState(initial = null)

    val connState by client.socket.state.collectAsState()
    // 底栏「消息」蓝点：与会话行同一份数据现算，口径见 TabUnread（三端同口径）
    val tabUnread = remember(conversations) { TabUnread.count(conversations.orEmpty()) }

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
    /**
     * 「关掉详情页，回聊天页顺带做一件事」的待办（开搜索 / 定位到某条）。
     * 为什么要绕这一道、为什么两件事合成一个类型，见 [ChatArm]。
     */
    var chatArm by remember { mutableStateOf(ChatArm()) }

    var menuFor by remember { mutableStateOf<ConversationEntity?>(null) }
    var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val scope = rememberCoroutineScope()

    // 底部 Tab 栏由各 Tab 的**根页**自己画（[TabRoot]），二级页整屏铺满——判据 PushNav.showsTabBar。
    // 此前底栏画在这一层、各 Tab 的二级页在它上面的内容区里原地切换，于是一直挂着（2026-09-15 用户报）
    val bottomBar: @Composable () -> Unit = {
        BottomBar(current = tab, unread = tabUnread, onSelect = { tab = it })
    }

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
                when (tab) {
                    Tab.Chats -> ChatsHost(
                        client = client,
                        conversations = conversations,
                        connected = connState == ConnState.Connected,
                        knownFriends = knownFriends,
                        onOpenChat = { openConv = it },
                        onLongPress = { c, rect -> menuFor = c; menuAnchor = rect },
                        bottomBar = bottomBar,
                    )
                    Tab.Contacts -> ContactsHost(client = client, onOpenChat = { openConv = it }, bottomBar = bottomBar)
                    Tab.Me -> MeHost(
                        client = client, onLogout = onLogout, bottomBar = bottomBar,
                        onOpenChat = { openConv = it },
                    )
                }
                menuFor?.let { target ->
                    ConversationMenu(client, target, menuAnchor, scope, onDismiss = { menuFor = null })
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
                            onOpenInfo = { infoTab = null; infoGallery = false; infoForConv = conv },
                            onOpenMediaGallery = {
                                infoTab = com.libeyond.imandroid.data.DetailTab.Media
                                infoGallery = true
                                infoForConv = conv
                            },
                            arm = chatArm,
                            onArmConsumed = { chatArm = ChatArm() },
                            covered = covered,
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
                            InfoPage(client, info, knownFriends, infoTab, infoGallery,
                                onOpenChat = { stub -> infoForConv = null; openConv = stub },
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
    onDismiss: () -> Unit,
) {
    MessageContextMenu(
        anchor = anchor,
        // 会话行是整行全宽的，菜单靠左（跟着行的起始边，与 iOS 的 preview 锚点同侧）
        mine = false,
        items = ConversationActions
            .availableFor(target.pinnedAt, target.muted, target.markedUnread, target.unread)
            .map { a ->
                SheetItem(a.label, a.destructive, icon = convActionIcon(a)) {
                    scope.launch {
                        runCatching {
                            when (a) {
                                ConversationAction.Pin -> settings(client, target, pinnedAt = System.currentTimeMillis())
                                ConversationAction.Unpin -> settings(client, target, pinnedAt = 0)
                                ConversationAction.Mute -> settings(client, target, muted = true)
                                ConversationAction.Unmute -> settings(client, target, muted = false)
                                ConversationAction.MarkUnread -> settings(client, target, markedUnread = true)
                                ConversationAction.MarkRead -> settings(client, target, markedUnread = false)
                                ConversationAction.Delete -> client.conversationsApi.delete(target.convId)
                            }
                        }
                        client.messages.refreshConversations()
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
    /** 先落在哪个页签；null = 各自默认。 */
    initialTab: com.libeyond.imandroid.data.DetailTab?,
    /** 这一趟只当会话媒体库用（收起头部与页签条）。 */
    galleryOnly: Boolean,
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
            onBack = onBack,
            onLeft = onLeft,
        )
    } else {
        ChatDetailHost(
            client = client,
            conv = conv,
            knownFriends = knownFriends,
            onSearchInChat = { onArm(ChatArm(openSearch = true)) },
            onLocateInChat = { seq -> onArm(ChatArm(locateSeq = seq)) },
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
) {
    client.conversationsApi.updateSettings(conv.convId, pinnedAt, muted, markedUnread)
}

@Composable
private fun BottomBar(current: Tab, unread: Int, onSelect: (Tab) -> Unit) {
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
                            contentDescription = t.label,
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(if (selected) c.accent else c.textTertiary),
                        )
                        if (t == Tab.Chats && unread > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .background(c.unreadBadge, androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                    Text(
                        text = t.label,
                        color = if (selected) c.accent else c.textTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
