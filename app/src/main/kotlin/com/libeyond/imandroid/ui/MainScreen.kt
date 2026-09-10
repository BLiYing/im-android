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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.ConversationListScreen
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

    val conversations by remember(owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeConversations(owner)
    }.collectAsState(initial = emptyList())

    val connState by client.socket.state.collectAsState()
    val totalUnread by remember(owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeTotalUnread(owner)
    }.collectAsState(initial = 0)

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
    /**
     * 「关掉详情页，回聊天页顺带做一件事」的待办（开搜索 / 定位到某条）。
     * 为什么要绕这一道、为什么两件事合成一个类型，见 [ChatArm]。
     */
    var chatArm by remember { mutableStateOf(ChatArm()) }
    val conv = openConv
    val infoConv = infoForConv
    if (conv != null || infoConv != null) {
        // 详情页**盖在**聊天页之上，聊天页不出组合（iOS push 之后底下那个 VC 还活着，同构）。
        // 此前是二选一的 `return`：进详情就把 ChatHost 整个移出组合，回来时列表状态从头建、
        // 按首屏规则重新定位——停在历史里点进详情，回来被甩回首条未读或底部（设计稿 #10）。
        Box(Modifier.fillMaxSize()) {
            if (conv != null) {
                // 换会话（群资料里点成员「发消息」）要整页重建：列表位置、输入框、覆盖层都是按会话的
                key(conv.convId) {
                    ChatHost(
                        client = client,
                        conv = conv,
                        onBack = { openConv = null },
                        onOpenInfo = { infoForConv = conv },
                        arm = chatArm,
                        onArmConsumed = { chatArm = ChatArm() },
                        covered = infoConv != null,
                    )
                }
            }
            if (infoConv != null) {
                Box(Modifier.fillMaxSize().blockPointerInput()) {
                    InfoPage(client, infoConv, knownFriends,
                        onOpenChat = { stub -> infoForConv = null; openConv = stub },
                        onArm = { arm -> infoForConv = null; chatArm = arm },
                        onBack = { infoForConv = null },
                        onLeft = { infoForConv = null; openConv = null },
                    )
                }
            }
        }
        return
    }

    var menuFor by remember { mutableStateOf<ConversationEntity?>(null) }
    var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val scope = rememberCoroutineScope()


    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(IMTheme.colors.groupedBackground)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Chats -> ConversationListScreen(
                    conversations = conversations,
                    onOpen = { openConv = it },
                    onLongPress = { conv, rect -> menuFor = conv; menuAnchor = rect },
                    onSettings = { tab = Tab.Me },
                    connected = connState == ConnState.Connected,
                )
                Tab.Contacts -> ContactsHost(client = client, onOpenChat = { openConv = it })
                Tab.Me -> MeHost(client = client, onLogout = onLogout)
            }
        }
        BottomBar(current = tab, unread = totalUnread, onSelect = { tab = it })
    }

    // —— 会话长按菜单（CHAT_UX §12/§14）——
    val target = menuFor
    if (target != null) {
        // **不是底部弹窗**：iOS 会话列表长按走的是 UIContextMenu（原位、贴着那一行）。
        // 底部弹窗把「操作哪一条」这个信息丢了——手指在屏幕上半部长按，眼睛却要跑到底部找菜单。
        // 与消息长按共用同一个组件，两处交互才一致。
        MessageContextMenu(
            anchor = menuAnchor,
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
            onDismiss = { menuFor = null },
        )
    }
    }
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
            onBack = onBack,
        )
    }
}

/**
 * 吞掉落在这一层的全部触摸。覆盖页底下的聊天页还在组合里，Compose 的命中测试会把
 * 上层没接住的触摸（详情页的留白处）继续交给下层兄弟——不拦的话，点详情页空白会点到看不见的气泡上。
 */
private fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
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
