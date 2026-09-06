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
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.ui.screens.ContactsScreen
import com.libeyond.imandroid.ui.screens.NewFriendsScreen
import com.libeyond.imandroid.ui.screens.UserSearchScreen
import kotlinx.coroutines.launch

private enum class ContactsPage { List, NewFriends, Search }

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

    // 二级页的返回键回到通讯录列表，不退出 App
    if (page != ContactsPage.List) BackHandler { page = ContactsPage.List }

    when (page) {
        ContactsPage.List -> ContactsScreen(
            friends = accepted,
            pendingCount = pending.size,
            onOpenNewFriends = { page = ContactsPage.NewFriends },
            onOpenSearch = { page = ContactsPage.Search; searched = false; results = emptyList() },
            onOpenFriend = { f -> onOpenChat(client.conversationStubFor(f.userId, f.displayName, f.avatarUrl)) },
        )

        ContactsPage.NewFriends -> NewFriendsScreen(
            pending = pending,
            requested = requested,
            onAccept = { f -> scope.launch { runCatching { client.contacts.accept(f.userId) }; reload() } },
            onReject = { f -> scope.launch { runCatching { client.contacts.reject(f.userId) }; reload() } },
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
