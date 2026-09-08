package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ContactsScreen
import com.libeyond.imandroid.ui.screens.CreateGroupScreen
import com.libeyond.imandroid.ui.screens.GroupListScreen
import com.libeyond.imandroid.ui.screens.NewFriendsScreen
import com.libeyond.imandroid.ui.screens.UserSearchScreen
import kotlinx.coroutines.launch

private enum class ContactsPage { List, NewFriends, Search, CreateGroup, Groups, Profile }

/**
 * 通讯录接线层：好友列表 / 新的朋友 / 找人。
 *
 * @param onOpenChat 点好友发消息——由 [MainScreen] 负责真正打开聊天页。
 */
@Composable
fun ContactsHost(client: IMClient, onOpenChat: (ConversationEntity) -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(ContactsPage.List) }
    var friends by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserCard>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }

    var groupName by remember { mutableStateOf("") }
    var groupPicks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf("") }
    // **群上限读服务端配置，不硬编码**（要装更多人走大群，不是调大这个数）
    var maxMembers by remember { mutableStateOf(0) }
    // 「群聊」入口：我加入的群（GET /groups），**不是**会话列表的子集——没聊过的群也在这里
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var groupsLoading by remember { mutableStateOf(false) }
    // 点好友先进**资料页**，不直接进聊天（三端统一的微信式口径）
    var profileOf by remember { mutableStateOf<FriendEntry?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { maxMembers = client.conversationsApi.serverConfig().maxGroupMembers }
    }

    suspend fun reload() {
        try {
            friends = client.contacts.friends()
        } catch (e: ApiException) {
            // 拉不到好友列表不该白屏——保留上一次的列表，只记日志
        }
    }

    LaunchedEffect(Unit) { reload() }

    // **收到任意 friend 帧即重新拉列表**，event 只作语义/日志（PROTOCOL §6.5）。
    LaunchedEffect(Unit) { client.friendEvents.collect { reload() } }

    val accepted = remember(friends) { friends.filter { it.status == FriendEntry.ACCEPTED } }
    val pending = remember(friends) { friends.filter { it.status == FriendEntry.PENDING } }
    val requested = remember(friends) { friends.filter { it.status == FriendEntry.REQUESTED } }
    val relations = remember(friends) { friends.associate { it.userId to it.status } }

    // 二级页的返回键回到通讯录列表，不退出 App。
    // **资料页自己带 BackHandler**（UserProfileHost 里），这里不能再截一层，否则要按两次。
    if (page != ContactsPage.List && page != ContactsPage.Profile) {
        BackHandler { page = ContactsPage.List }
    }

    toast?.let { t -> IMToast(t) { toast = null } }

    when (page) {
        ContactsPage.List -> ContactsScreen(
            friends = accepted,
            pendingCount = pending.size,
            onOpenNewFriends = { page = ContactsPage.NewFriends },
            onOpenSearch = { page = ContactsPage.Search; searched = false; results = emptyList() },
            onOpenGroups = {
                page = ContactsPage.Groups
                scope.launch {
                    groupsLoading = true
                    runCatching { client.groups.myGroups() }
                        .onSuccess { groups = it }
                        // 拉不到就留着上一次的列表 + 一句吐司，别把页面停在"还没有加入群聊"上
                        // ——那句空态是**结论**，网络失败时它是假的。
                        .onFailure { toast = "群列表加载失败" }
                    groupsLoading = false
                }
            },
            onComingSoon = { name -> toast = "$name 还没做" },
            // **先进资料页，不直接进聊天**（微信式，三端统一：群成员行、通讯录行都是这个口径）
            onOpenFriend = { f -> profileOf = f; page = ContactsPage.Profile },
        )

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
            onCreate = {
                page = ContactsPage.CreateGroup
                groupName = ""; groupPicks = emptySet(); createError = ""
            },
            onBack = { page = ContactsPage.List },
        )

        ContactsPage.Profile -> {
            val f = profileOf
            if (f == null) {
                page = ContactsPage.List
            } else {
                UserProfileHost(
                    client = client,
                    userId = f.userId,
                    knownRelation = f.status,
                    seed = UserCard(
                        userId = f.userId, username = f.username,
                        nickname = f.nickname, avatarUrl = f.avatarUrl, remark = f.remark,
                    ),
                    onSendMessage = { u ->
                        onOpenChat(client.conversationStubFor(u.userId, u.displayName, u.avatarUrl))
                    },
                    onBack = { page = ContactsPage.List },
                )
            }
        }

        ContactsPage.NewFriends -> NewFriendsScreen(
            pending = pending,
            requested = requested,
            onAccept = { f -> scope.launch { runCatching { client.contacts.accept(f.userId) }; reload() } },
            onReject = { f -> scope.launch { runCatching { client.contacts.reject(f.userId) }; reload() } },
            onBack = { page = ContactsPage.List },
        )

        ContactsPage.CreateGroup -> CreateGroupScreen(
            name = groupName,
            onNameChange = { groupName = it },
            friends = accepted,
            selected = groupPicks,
            onToggle = { id ->
                groupPicks = if (id in groupPicks) groupPicks - id else groupPicks + id
            },
            maxMembers = maxMembers,
            busy = creating,
            error = createError,
            onCreate = {
                scope.launch {
                    creating = true; createError = ""
                    try {
                        client.groups.create(groupName.trim(), groupPicks.toList())
                        client.messages.refreshConversations()
                        page = ContactsPage.List
                    } catch (e: ApiException) {
                        createError = if (e.isTransport) "网络请求失败" else e.message
                    } finally { creating = false }
                }
            },
            onBack = { page = ContactsPage.List },
        )

        ContactsPage.Search -> UserSearchScreen(
            query = query,
            onQueryChange = { query = it },
            onSearch = {
                scope.launch {
                    searching = true; searchError = ""
                    try {
                        results = client.contacts.search(query.trim())
                        searched = true
                    } catch (e: ApiException) {
                        searchError = if (e.isTransport) "网络请求失败" else e.message
                    } finally { searching = false }
                }
            },
            results = results,
            relations = relations,
            searching = searching,
            searched = searched,
            error = searchError,
            onAdd = { u ->
                scope.launch {
                    runCatching {
                        // 已是对方的待确认申请 → 同意；否则发起申请
                        if (relations[u.userId] == FriendEntry.PENDING) client.contacts.accept(u.userId)
                        else client.contacts.request(u.userId)
                    }
                    reload()
                }
            },
            onOpenChat = { u -> onOpenChat(client.conversationStubFor(u.userId, u.displayName, u.avatarUrl)) },
            onBack = { page = ContactsPage.List },
        )
    }
}
