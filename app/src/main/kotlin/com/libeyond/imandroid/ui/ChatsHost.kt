package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanQrCode
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.data.ChatsPage
import com.libeyond.imandroid.data.ConversationListPhase
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.LocalOpenQrScan
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.ConversationListScreen

/**
 * 「消息」Tab：会话列表 + 右上角 ＋ 推出去的「添加朋友」「新建群聊」两页。
 *
 * 结构对齐 iOS `IMConversationListViewController` 的 `plusTapped:`：锚在 ＋ 上的菜单三项
 * 扫一扫 / 新建群聊 / 添加好友，后两页在**本 Tab 内** push，返回回到会话列表；扫一扫是全屏取景页，
 * 挂在更外层的 [QrRouteHost]（点 [LocalOpenQrScan] 打开），不在本 Tab 内 push。
 *
 * @param conversations 本地库这一份；null = 库还没回第一份（空态判据见 [ConversationListPhase]）。
 */
@Composable
fun ChatsHost(
    client: IMClient,
    conversations: List<ConversationEntity>?,
    connected: Boolean,
    knownFriends: Map<String, FriendEntry>,
    onOpenChat: (ConversationEntity) -> Unit,
    onLongPress: (ConversationEntity, Rect) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val owner = client.uid.orEmpty()
    var page by remember { mutableStateOf(ChatsPage.List) }
    var plusAnchor by remember { mutableStateOf<Rect?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val openScan = LocalOpenQrScan.current
    val listed by client.messages.listedConversations.collectAsState()
    val phase = ConversationListPhase.of(
        localCount = conversations?.size,
        // 只认本账号那次拉取：换号后上一个账号的条数不能拿来给新账号下结论
        serverCount = listed?.takeIf { it.owner == owner }?.count,
    )

    // 同 ContactsHost：挂在转场外面、按目标页判
    if (page != ChatsPage.List) BackHandler { page = ChatsPage.List }

    PushTransition(targetState = page, depthOf = { it.depth }) { p ->
        when (p) {
            ChatsPage.List -> TabRoot(bottomBar) {
                ConversationListScreen(
                    conversations = conversations.orEmpty(),
                    phase = phase,
                    myUid = owner,
                    // 会话列表只有全局好友表可用，没有群成员表（那是群资料里的东西）——
                    // 与 iOS 列表 cell 的 `lastPreviewTextForSelfUID:` 同一条退化路径（群昵称传 nil）。
                    localNameOf = { uid -> knownFriends[uid]?.displayName },
                    onOpen = onOpenChat,
                    onLongPress = onLongPress,
                    onPlus = { plusAnchor = it },
                    connected = connected,
                )
            }
            ChatsPage.AddFriend -> AddFriendHost(
                client = client,
                onOpenChat = onOpenChat,
                onBack = { page = ChatsPage.List },
            )
            ChatsPage.CreateGroup -> CreateGroupHost(
                client = client,
                seedFriends = knownFriends.values.toList(),
                // 建成直接进新群（同 iOS startNewGroup：先回会话列表，再进群聊）
                onCreated = { g ->
                    page = ChatsPage.List
                    onOpenChat(client.groupConversationStubFor(g.convId, g.name, g.avatarUrl))
                },
                onBack = { page = ChatsPage.List },
            )
        }
    }

    plusAnchor?.let { anchor ->
        MessageContextMenu(
            anchor = anchor,
            // ＋ 在右上角：菜单靠右、贴在按钮下方
            mine = true,
            items = listOf(
                SheetItem("扫一扫", icon = Lucide.ScanQrCode) {
                    openScan?.invoke() ?: run { toast = "扫一扫暂不可用" }
                },
                SheetItem("新建群聊", icon = Lucide.Users) { page = ChatsPage.CreateGroup },
                SheetItem("添加好友", icon = Lucide.UserPlus) { page = ChatsPage.AddFriend },
            ),
            onDismiss = { plusAnchor = null },
        )
    }

    // 吐司放最后：画在页面之前会被页面整个盖住（ContactsHost 同一条纪律）
    toast?.let { t -> IMToast(t) { toast = null } }
}
