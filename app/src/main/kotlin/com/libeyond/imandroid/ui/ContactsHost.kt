package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ContactsPage
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.data.FriendAction
import com.libeyond.imandroid.data.recentAdded
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.screens.ContactsScreen
import com.libeyond.imandroid.ui.screens.GroupListScreen
import com.libeyond.imandroid.ui.screens.NewFriendsScreen
import kotlinx.coroutines.launch

/**
 * 通讯录接线层：好友列表 / 新的朋友 / 群聊 / 资料页。
 * 「添加朋友」「建群」两页的状态在 [AddFriendHost] / [CreateGroupHost]——消息页 ＋ 菜单也进这两页。
 *
 * @param onOpenChat 点好友发消息——由 [MainScreen] 负责真正打开聊天页。
 * @param bottomBar 底部 Tab 栏，**只在列表根页画**；二级页整屏铺满（判据 `PushNav.showsTabBar`，外壳 [TabRoot]）。
 */
@Composable
fun ContactsHost(
    client: IMClient,
    onOpenChat: (ConversationEntity) -> Unit,
    bottomBar: @Composable () -> Unit,
    /** 待我确认的数量变了就报给外壳，通讯录 Tab 角标用（口径只数 pending，见 NEW_FRIENDS_DESIGN §3）。 */
    onPendingCount: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(ContactsPage.List) }
    var friends by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    /** 网络好友表是否至少成功拉到过一次：没拉到前 pending 是空表的 0，不能拿去覆盖外壳已有的角标计数。 */
    var friendsLoaded by remember { mutableStateOf(false) }

    // 「群聊」入口：我加入的群（GET /groups），**不是**会话列表的子集——没聊过的群也在这里
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var groupsLoading by remember { mutableStateOf(false) }
    // 点好友先进**资料页**，不直接进聊天（三端统一的微信式口径）
    var profileOf by remember { mutableStateOf<FriendEntry?>(null) }
    /** 资料页从哪一页进来的：返回落回那一页（从「新的朋友」进的要回「新的朋友」，不是通讯录首页）。 */
    var profileOrigin by remember { mutableStateOf(ContactsPage.List) }
    /** 正在同意 / 拒绝的申请方 userId：防连点（同一人同时发两次请求）。 */
    var busyIds by remember { mutableStateOf(emptySet<String>()) }
    var toast by remember { mutableStateOf<String?>(null) }
    /** 通讯录搜索无结果时带进加好友页的初始关键词（点右上角「添加朋友」为空）。 */
    var addFriendQuery by remember { mutableStateOf("") }
    /** 通讯录搜索词：放 Host 层，进二级页（资料 / 加好友）再返回不丢。 */
    var searchQuery by remember { mutableStateOf("") }
    /** 通讯录列表滚动位置：放 Host 层，进二级页再返回不回顶。 */
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    /**
     * 左滑「删除」待确认的那位好友（null = 没有）。
     *
     * **iOS 那侧左滑删除没有二次确认**——本端刻意加上：删好友不可撤销，而左滑 + 点一下只有两个手势。
     * im-web 的删除好友与本端资料页的删除好友都有确认，三处里两处有，缺的那处更像疏漏
     * （`docs/UI_PARITY_IOS.md` §4.5.1）。
     */
    var confirmRemove by remember { mutableStateOf<FriendEntry?>(null) }

    val owner = client.uid.orEmpty()
    // 断网回退（任务 5）：先用本地快照当首屏种子（网络刷新回来会覆盖），冷启动离线不再是一张空表。
    // 种子只在列表还空着时落地——网络若先到了就别拿旧快照盖掉
    LaunchedEffect(owner) {
        runCatchingCancellable { client.roster.cachedFriends(owner) }.onSuccess { if (friends.isEmpty()) friends = it }
        runCatchingCancellable { client.roster.cachedGroups(owner) }.onSuccess { if (groups.isEmpty()) groups = it }
    }

    suspend fun reload() {
        try {
            friends = client.contacts.friends()
            friendsLoaded = true
            // 成功才落库（整表覆盖，空也写；只存 accepted）；写失败只记日志，不影响界面
            runCatchingCancellable { client.roster.saveFriends(owner, friends) }
                .onFailure { IMLog.tag("IM.Contacts").w("roster_save_failed", "err" to it.javaClass.simpleName) }
        } catch (e: ApiException) {
            // 拉不到好友列表不该白屏——保留上一次的列表。**但必须留痕**：
            // 这里原本是个空 catch（注释说"只记日志"，一行日志都没有），而 `reload()` 是
            // 拉黑/解除/删除之后刷新 UI 的唯一路径——弱网下动作成功、reload 静默失败，
            // 用户看到的是「已拉黑」的吐司 + 一行没变的列表，会以为没生效再点一次
            // （2026-09-09 `/code-review` 抓出）
            IMLog.tag("IM.Contacts").w("contacts_reload_failed", "code" to e.code)
        }
    }

    /** 「群聊」列表页的刷新：进页时、通讯录建群成功回到它时。 */
    fun loadGroups() {
        scope.launch {
            groupsLoading = true
            runCatching { client.groups.myGroups() }
                .onSuccess {
                    groups = it
                    runCatchingCancellable { client.roster.saveGroups(owner, it) }
                }
                // 拉不到就留着上一次的列表 + 一句吐司，别把页面停在"还没有加入群聊"上
                // ——那句空态是**结论**，网络失败时它是假的。
                .onFailure { toast = Str.s(R.string.contacts_groups_load_failed) }
            groupsLoading = false
        }
    }

    LaunchedEffect(Unit) {
        // 群列表与好友列表互不依赖：并发拉，别让弱网下的好友请求拖慢群搜索（原先串行）
        launch {
            // 通讯录搜索要搜群名：静默刷新一次我加入的群（失败就用上面的离线快照，不打扰，但留痕）
            runCatchingCancellable { client.groups.myGroups() }
                .onSuccess {
                    groups = it
                    runCatchingCancellable { client.roster.saveGroups(owner, it) }
                }
                .onFailure { IMLog.tag("IM.Contacts").w("groups_refresh_failed", "err" to it.javaClass.simpleName) }
        }
        reload()
    }

    // **收到任意 friend 帧即重新拉列表**，event 只作语义/日志（PROTOCOL §6.5）。
    LaunchedEffect(Unit) { client.friendEvents.collect { reload() } }

    val accepted = remember(friends) { friends.filter { it.status == FriendEntry.ACCEPTED } }
    val pending = remember(friends) { friends.filter { it.status == FriendEntry.PENDING } }
    val requested = remember(friends) { friends.filter { it.status == FriendEntry.REQUESTED } }
    val addedRecent = remember(friends) { recentAdded(friends, System.currentTimeMillis()) }
    LaunchedEffect(pending.size, friendsLoaded) { if (friendsLoaded) onPendingCount(pending.size) }

    /** 同意 / 拒绝：忙态防连点，失败给吐司（原先 runCatching 吞错，点了没反应）。 */
    fun answerRequest(f: FriendEntry, call: suspend () -> Unit) {
        if (f.userId in busyIds) return
        busyIds = busyIds + f.userId
        scope.launch {
            runCatchingCancellable { call() }
                .onFailure { toast = it.userMessage(Str.s(R.string.common_action_failed)) }
            reload()
            busyIds = busyIds - f.userId
        }
    }

    // 二级页的返回键回到通讯录列表，不退出 App。
    // **资料页自己带 BackHandler**（UserProfileHost 里），这里不能再截一层，否则要按两次。
    // 挂在转场**外面**、按目标页判：转场中滑走的那一页已被隔离，这里只认要去的那一页
    if (page != ContactsPage.List && page != ContactsPage.Profile) {
        BackHandler { page = page.parent }
    }

    // 从「新的朋友」进的资料页比它深一层，才是 push（同深度会变成淡入淡出）
    PushTransition(
        targetState = page,
        depthOf = { if (it == ContactsPage.Profile && profileOrigin == ContactsPage.NewFriends) 2 else it.depth },
    ) { p ->
        when (p) {
            ContactsPage.List -> TabRoot(bottomBar) {
                ContactsScreen(
                    listState = listState,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    friends = accepted,
                    pendingCount = pending.size,
                    onOpenNewFriends = { page = ContactsPage.NewFriends },
                    onFriendAction = { f, a ->
                        when (a) {
                            // 破坏性 + 不可撤销 → 先确认
                            FriendAction.Delete -> confirmRemove = f
                            FriendAction.Block -> scope.launch {
                                runCatchingCancellable { client.contacts.block(f.userId) }
                                    .onSuccess { toast = Str.s(R.string.common_blocked) }
                                    .onFailure { toast = it.userMessage(Str.s(R.string.friend_block_failed_toast)) }
                                reload()
                            }
                            FriendAction.Unblock -> scope.launch {
                                runCatchingCancellable { client.contacts.unblock(f.userId) }
                                    .onSuccess { toast = Str.s(R.string.friend_block_undone) }
                                    .onFailure { toast = it.userMessage(Str.s(R.string.common_action_failed)) }
                                reload()
                            }
                        }
                    },
                    onAddFriend = { addFriendQuery = ""; page = ContactsPage.Search },
                    groups = groups,
                    onOpenGroup = { g -> onOpenChat(client.groupConversationStubFor(g.convId, g.name, g.avatarUrl)) },
                    onSearchUser = { q -> addFriendQuery = q; page = ContactsPage.Search },
                    onOpenGroups = {
                        page = ContactsPage.Groups
                        loadGroups()
                    },
                    onComingSoon = { name -> toast = Str.s(R.string.contacts_coming_soon, name) },
                    // **先进资料页，不直接进聊天**（微信式，三端统一：群成员行、通讯录行都是这个口径）
                    onOpenFriend = { f -> profileOf = f; profileOrigin = ContactsPage.List; page = ContactsPage.Profile },
                )
            }

            ContactsPage.Groups -> GroupListScreen(
                groups = groups,
                loading = groupsLoading,
                myUid = client.uid.orEmpty(),
                localNameOf = { uid ->
                    friends.firstOrNull { it.userId == uid }?.let { DisplayName.ofFriend(it) }
                },
                onOpen = { g ->
                    onOpenChat(client.groupConversationStubFor(g.convId, g.name, g.avatarUrl))
                },
                onCreate = { page = ContactsPage.CreateGroup },
                onBack = { page = ContactsPage.List },
            )

            ContactsPage.Profile -> {
                val f = profileOf
                if (f == null) {
                    page = ContactsPage.List
                } else {
                    MemberProfileHost(
                        client = client,
                        userId = f.userId,
                        knownFriends = remember(friends) { friends.associateBy { it.userId } },
                        name = f.nickname,
                        avatarUrl = f.avatarUrl,
                        onOpenChat = onOpenChat,
                        // 信息页里改的备注不回调：退回时重拉好友表，列表那一行才不显编辑前的旧值
                        onBack = { page = profileOrigin; scope.launch { reload() } },
                    )
                }
            }

            ContactsPage.NewFriends -> NewFriendsScreen(
                pending = pending,
                requested = requested,
                added = addedRecent,
                onOpen = { f -> profileOf = f; profileOrigin = ContactsPage.NewFriends; page = ContactsPage.Profile },
                onAccept = { f -> answerRequest(f) { client.contacts.accept(f.userId) } },
                onReject = { f -> answerRequest(f) { client.contacts.reject(f.userId) } },
                onBack = { page = ContactsPage.List },
            )

            ContactsPage.CreateGroup -> CreateGroupHost(
                client = client,
                seedFriends = accepted,
                // 建成直接进新群（同 iOS IMGroupListViewController createTapped：回**群列表页**再进群聊，
                // 从聊天退回来落在群列表、且列表里已有新群）
                onCreated = { g ->
                    page = ContactsPage.CreateGroup.parent
                    loadGroups()
                    onOpenChat(client.groupConversationStubFor(g.convId, g.name, g.avatarUrl))
                },
                // 返回回到入口那一页（群聊列表），不是通讯录首页（2026-10-07 用户报）
                onBack = { page = ContactsPage.CreateGroup.parent },
            )

            ContactsPage.Search -> AddFriendHost(
                client = client,
                onOpenChat = onOpenChat,
                onChanged = { scope.launch { reload() } },
                onBack = { page = ContactsPage.List },
                initialQuery = addFriendQuery,
            )
        }
    }

    // 删除好友的二次确认（理由见 confirmRemove 的注释）
    confirmRemove?.let { f ->
        IMConfirmDialog(
            title = stringResource(R.string.friend_menu_delete),
            message = stringResource(R.string.friend_delete_confirm_message, f.displayName),
            confirmText = stringResource(R.string.common_delete),
            destructive = true,
            onDismiss = { confirmRemove = null },
            onConfirm = {
                confirmRemove = null
                scope.launch {
                    runCatchingCancellable { client.contacts.remove(f.userId) }
                        .onSuccess { toast = Str.s(R.string.friend_delete_done) }
                        .onFailure { toast = it.userMessage(Str.s(R.string.net_fallback_delete_failed)) }
                    reload()
                }
            },
        )
    }

    // toast 放最后：它是一层 fillMaxSize 的浮层，**画在页面之前会被页面整个盖住**。
    // 本文件原先就画在 `when (page)` 之前——也就是说这一页的吐司一直是看不见的（既有 bug，
    // 2026-09-09 顺手修）。同一条纪律在 ChatHost / ChatDetailHost / GroupInfoHost 里都写着。
    toast?.let { t -> IMToast(t) { toast = null } }
}
