package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.ScanQrCode
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.data.ChatsPage
import com.libeyond.imandroid.data.ChatFolderLogic
import com.libeyond.imandroid.data.ConversationListPhase
import com.libeyond.imandroid.data.ConversationSelection
import com.libeyond.imandroid.data.Presence
import com.libeyond.imandroid.data.PresenceDisplay
import com.libeyond.imandroid.data.SettingsRoute
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.LocalOpenQrScan
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.ConversationEditBar
import com.libeyond.imandroid.ui.screens.ConversationListScreen
import com.libeyond.imandroid.ui.screens.FolderScrollMemory
import kotlinx.coroutines.launch

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
    conn: ConnState,
    knownFriends: Map<String, FriendEntry>,
    onOpenChat: (ConversationEntity) -> Unit,
    /** 点聊天记录命中：进会话并定位到那条（[MainScreen] 把 seq 交给聊天页，同详情页「定位」）。 */
    onOpenChatAt: (ConversationEntity, Long) -> Unit,
    onLongPress: (ConversationEntity, Rect) -> Unit,
    /** 点设置搜索命中：外壳切到「我」tab 并开到目标页。 */
    onOpenSetting: (SettingsRoute) -> Unit = {},
    bottomBar: @Composable () -> Unit,
    /** 定时免打扰到期刷新用的当前时刻（NOTIFICATIONS_P1_DESIGN §4.4）——喂给 [ConversationListScreen]
     *  的铃铛/未读徽标；由调用方（`MainScreen`）算一次，不在这里另起一份定时器。 */
    muteNow: Long = System.currentTimeMillis(),
    /** 会话列表滚动位置：进会话时本 Tab 整层离开组合，状态必须挂在更外层（`MainScreen`）才不丢。 */
    listState: LazyListState,
    /** 聊天文件夹当前选中项（null = 「全部」）与每 Tab 滚动位置：同上，挂在外壳才不随进会话丢。 */
    selectedFolderId: String? = null,
    onSelectFolder: (String?) -> Unit = {},
    scrollMemory: FolderScrollMemory = remember { FolderScrollMemory() },
) {
    val owner = client.uid.orEmpty()
    val pendingCounts by client.pendingCounts.collectAsState()
    // 在线态绿点：数据链路早已在（HTTP 快照 seed + presence 帧增量更新，见 data/Presence.kt），
    // 缺的只是这一层读取——同 iOS 的 `peerPresence.isOnline`，不额外发 watch（下线态本就靠
    // 下次刷新收敛，见 IMConversationListViewController.m 的同款注释，两端行为一致）。
    val presenceMap by client.presence.presence.collectAsState()
    // 「租约到期」是纯粹的时间流逝，不触发任何回调；只用调用时刻的 `System.currentTimeMillis()`
    // 算一次、往后不重算的话，用户静止不动时绿点会**永远**停在「在线」（Presence.kt 同一条纪律，
    // `rememberChatSubtitle` 已按此心跳重算，这里此前漏了）。同用 `Presence.TICK_MS` 心跳。
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Presence.TICK_MS)
            tick = System.currentTimeMillis()
        }
    }
    // `remember` 稳定它的引用：presenceMap/tick 没变时，ChatsHost 因别的状态（吐司/菜单锚点等）
    // 重组不会连带让每一行都重算——真正"哪个 uid 的在线态变了"仍由下面 presenceMap 整份驱动
    // （本仓会话列表规模不大，未按 uid 拆分订阅；群成员表那种量级才值得再拆）。
    val onlineOf = remember(presenceMap, tick) {
        { uid: String ->
            val p = presenceMap[uid]
            p != null && Presence.display(p.status, p.onlineUntil, p.lastSeen, tick) is PresenceDisplay.Online
        }
    }
    var page by remember { mutableStateOf(ChatsPage.List) }
    // 聊天文件夹：整表快照（Tab 条 / 编辑页共用）+ 长按 Tab 的菜单 / 删除确认 / 去编辑的目标
    val folderSnap by client.chatFolderStore.state.collectAsState()
    var folderMenuFor by remember { mutableStateOf<Pair<com.libeyond.imandroid.data.ChatFolder, Rect>?>(null) }
    var folderDeleteFor by remember { mutableStateOf<com.libeyond.imandroid.data.ChatFolder?>(null) }
    var folderEditId by remember { mutableStateOf<String?>(null) }
    val folderScope = androidx.compose.runtime.rememberCoroutineScope()
    /** 「搜索用户「x」」带进加好友页的初始关键词；从加号进来为空。 */
    var addFriendQuery by remember { mutableStateOf("") }
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

    // —— 编辑模式（批量已读 / 删除，设计 CONV_LIST_EDIT_MODE_DESIGN）——
    val edit = remember { ConversationEditState() }
    val selectedFolder = folderSnap.folder(selectedFolderId)
    val shownConvs = remember(conversations, selectedFolder) { ChatFolderLogic.filter(conversations.orEmpty(), selectedFolder) }
    // 列表换新（新消息重排 / 他端删除 / 退群）：剔除已不存在的已选项，不退出编辑
    LaunchedEffect(shownConvs) { if (edit.editing) edit.selection = edit.selection.pruneTo(shownConvs) }
    // 切 Tab（含当前文件夹被他端删而回落「全部」）：清空选择、保持编辑
    var editScopeFolder by remember { mutableStateOf(selectedFolder?.id) }
    LaunchedEffect(selectedFolder?.id) {
        if (editScopeFolder != selectedFolder?.id) {
            editScopeFolder = selectedFolder?.id
            edit.selection = ConversationSelection()
        }
    }
    if (page == ChatsPage.List && edit.editing) BackHandler { edit.exit() }
    val selectedConvs = if (edit.editing) edit.selection.selectedIn(shownConvs) else emptyList()
    val markReadable = selectedConvs.filter { ConversationSelection.hasUnread(it) }
    // 确认框开着时已选项被剔空（他端删除 / 切范围）：框随之消失，标志也要复位，否则下次一勾选就弹出
    LaunchedEffect(selectedConvs.isEmpty()) { if (selectedConvs.isEmpty()) edit.confirmingDelete = false }
    val onBatchRead: () -> Unit = {
        if (!edit.busy && markReadable.isNotEmpty()) {
            edit.busy = true
            folderScope.launch {
                val r = runCatchingCancellable { batchMarkRead(client, markReadable) }.getOrNull()
                edit.busy = false
                if (r == null || r.failed.isNotEmpty()) {
                    toast = Str.s(R.string.conv_error_mark_failed) // 设计 §3：toast「标记失败」
                    if (r != null) edit.selection = edit.selection.deselect(r.succeeded.map { it.convId })
                } else {
                    edit.exit()
                }
            }
        }
    }
    val onBatchDelete: () -> Unit = {
        val targets = selectedConvs
        edit.confirmingDelete = false
        if (!edit.busy && targets.isNotEmpty()) {
            edit.busy = true
            folderScope.launch {
                val r = runCatchingCancellable { batchDelete(client, targets) }.getOrNull()
                edit.busy = false
                if (r == null || r.failed.isNotEmpty()) {
                    val failed = r?.failed?.size ?: targets.size
                    toast = Str.p(R.plurals.conv_edit_delete_failed, failed, failed)
                    // 成功的行已消失；失败的保持勾选，留在编辑模式，便于重试
                    if (r != null) edit.selection = edit.selection.deselect(r.succeeded.map { it.convId })
                } else {
                    edit.exit()
                }
            }
        }
    }
    // 编辑中：底栏插槽换成选择栏（独立一条、不与列表重叠；TabBar 隐藏）
    val rootBottomBar: @Composable () -> Unit = if (edit.editing) {
        {
            ConversationEditBar(
                allSelected = edit.selection.isAllSelectedIn(shownConvs),
                canSelectAll = shownConvs.isNotEmpty() && !edit.busy,
                canRead = markReadable.isNotEmpty() && !edit.busy,
                canDelete = selectedConvs.isNotEmpty() && !edit.busy,
                onSelectAll = {
                    edit.selection = if (edit.selection.isAllSelectedIn(shownConvs)) ConversationSelection() else edit.selection.selectAll(shownConvs)
                },
                onRead = onBatchRead,
                onDelete = { edit.confirmingDelete = true },
            )
        }
    } else {
        bottomBar
    }

    PushTransition(targetState = page, depthOf = { it.depth }) { p ->
        when (p) {
            ChatsPage.List -> TabRoot(rootBottomBar) {
                ConversationListScreen(
                    conversations = conversations.orEmpty(),
                    phase = phase,
                    myUid = owner,
                    // 会话列表只有全局好友表可用，没有群成员表（那是群资料里的东西）——
                    // 与 iOS 列表 cell 的 `lastPreviewTextForSelfUID:` 同一条退化路径（群昵称传 nil）。
                    localNameOf = { uid -> knownFriends[uid]?.displayName },
                    onlineOf = onlineOf,
                    pendingOf = { pendingCounts[it] ?: 0 },
                    onOpen = onOpenChat,
                    onLongPress = onLongPress,
                    onPlus = { plusAnchor = it },
                    onSearch = { page = ChatsPage.Search },
                    conn = conn,
                    nowMs = muteNow,
                    listState = listState,
                    folders = folderSnap.folders,
                    selectedFolderId = selectedFolderId,
                    onSelectFolder = onSelectFolder,
                    onFolderLongPress = { f, r -> folderMenuFor = f to r },
                    scrollMemory = scrollMemory,
                    editing = edit.editing,
                    selectedIds = edit.selection.ids,
                    onToggleSelect = { id -> edit.selection = edit.selection.toggle(id) },
                    onEnterEdit = { edit.enter() },
                    onExitEdit = { edit.exit() },
                )
            }
            ChatsPage.AddFriend -> AddFriendHost(
                client = client,
                onOpenChat = onOpenChat,
                onBack = { page = if (addFriendQuery.isEmpty()) ChatsPage.List else ChatsPage.Search },
                initialQuery = addFriendQuery,
            )
            ChatsPage.Search -> GlobalSearchHost(
                client = client,
                conversations = conversations.orEmpty(),
                knownFriends = knownFriends,
                onOpenChat = onOpenChat,
                onOpenChatAt = onOpenChatAt,
                onOpenSetting = onOpenSetting,
                onSearchUser = { q -> addFriendQuery = q; page = ChatsPage.AddFriend },
                onBack = { page = ChatsPage.List },
            )
            ChatsPage.FolderEdit -> ChatFolderEditHost(
                client = client,
                folderId = folderEditId,
                initialName = null,
                conversations = conversations.orEmpty(),
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
            // ＋ 在右上角：菜单靠右、贴在按钮下方；按钮弹出的小菜单，背景几乎不压暗（iOS IMPopoverCard）
            mine = true,
            popover = true,
            items = listOf(
                SheetItem(stringResource(R.string.conv_menu_scan), icon = Lucide.ScanQrCode) {
                    openScan?.invoke() ?: run { toast = Str.s(R.string.conv_menu_scan_unavailable) }
                },
                SheetItem(stringResource(R.string.conv_menu_new_group), icon = Lucide.Users) { page = ChatsPage.CreateGroup },
                SheetItem(stringResource(R.string.common_add_friend), icon = Lucide.UserPlus) { addFriendQuery = ""; page = ChatsPage.AddFriend },
            ),
            onDismiss = { plusAnchor = null },
        )
    }

    // 长按文件夹 Tab：编辑 / 删除（「全部」不触发）
    folderMenuFor?.let { (f, anchor) ->
        // 与 iOS IMPopoverCard / Web 右键菜单一致：贴在被长按的 Tab 正下方，左缘与 Tab 对齐（不再是底部 ActionSheet）
        MessageContextMenu(
            anchor = anchor,
            mine = false,
            popover = true,
            alignToAnchor = true,
            items = listOf(
                SheetItem(stringResource(R.string.folder_tab_menu_edit), icon = Lucide.Pencil) { folderEditId = f.id; page = ChatsPage.FolderEdit },
                SheetItem(stringResource(R.string.folder_tab_menu_delete), icon = Lucide.Trash2, destructive = true) { folderDeleteFor = f },
            ),
            onDismiss = { folderMenuFor = null },
        )
    }
    folderDeleteFor?.let { f ->
        com.libeyond.imandroid.ui.components.IMConfirmDialog(
            title = f.name,
            message = stringResource(R.string.folder_edit_delete_confirm, f.name),
            confirmText = stringResource(R.string.common_delete),
            destructive = true,
            onConfirm = {
                folderDeleteFor = null
                folderScope.launch {
                    runCatchingCancellable { client.chatFolderStore.delete(f.id) }
                        .onSuccess { if (selectedFolderId == f.id) onSelectFolder(null) }
                        .onFailure { toast = it.userMessage(Str.s(R.string.folder_toast_failed)) }
                }
            },
            onDismiss = { folderDeleteFor = null },
        )
    }

    // 批量删除确认（Android / Web 同口径：标题「删除 N 个会话？」+ 正文）
    if (edit.confirmingDelete && selectedConvs.isNotEmpty()) {
        val n = selectedConvs.size
        com.libeyond.imandroid.ui.components.IMConfirmDialog(
            title = androidx.compose.ui.res.pluralStringResource(R.plurals.conv_edit_delete_title, n, n),
            message = stringResource(R.string.conv_edit_delete_body),
            confirmText = stringResource(R.string.common_delete),
            destructive = true,
            onConfirm = onBatchDelete,
            onDismiss = { edit.confirmingDelete = false },
        )
    }

    // 吐司放最后：画在页面之前会被页面整个盖住（ContactsHost 同一条纪律）
    toast?.let { t -> IMToast(t) { toast = null } }
}
