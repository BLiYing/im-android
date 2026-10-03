package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.GlobalSearch
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.searchAllMessages
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.screens.GlobalSearchScreen

/**
 * 首页全局搜索宿主（对齐 iOS `IMGlobalSearchViewController`）：会话/联系人在内存里过滤，
 * 聊天记录走本地库 [searchAllMessages]（关键词变化后防抖 150ms，避免每敲一个字查一次库）。
 *
 * 点会话/联系人直接进聊天；点聊天记录进聊天并**定位到那条**（[onOpenChatAt]）；
 * 「搜索用户」下钻到加好友页（[onSearchUser]）。
 */
@Composable
fun GlobalSearchHost(
    client: IMClient,
    conversations: List<ConversationEntity>,
    knownFriends: Map<String, FriendEntry>,
    onOpenChat: (ConversationEntity) -> Unit,
    onOpenChatAt: (ConversationEntity, Long) -> Unit,
    onSearchUser: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val owner = client.uid.orEmpty()
    var query by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<GlobalSearch.RecordHit>>(emptyList()) }
    val titleOf = remember(knownFriends) { { c: ConversationEntity -> Forward.titleOf(c) { knownFriends[it]?.displayName } } }

    LaunchedEffect(query, conversations) {
        if (query.isBlank()) { records = emptyList(); return@LaunchedEffect }
        delay(150)
        val msgs = client.repo.searchAllMessages(owner, query)
        records = GlobalSearch.recordHits(msgs, conversations, query)
    }

    GlobalSearchScreen(
        query = query,
        onQueryChange = { query = it },
        convs = GlobalSearch.convHits(conversations, query, titleOf),
        friends = GlobalSearch.friendHits(knownFriends.values, query),
        records = records,
        titleOf = titleOf,
        onOpenConv = onOpenChat,
        onOpenFriend = { f ->
            if (f.userId.isNotEmpty() && f.userId != owner) onOpenChat(client.conversationStubFor(f.userId, f.displayName, f.avatarUrl))
        },
        onOpenRecord = { r -> onOpenChatAt(r.conv, r.msg.convSeq) },
        onSearchUser = { onSearchUser(query.trim()) },
        onBack = onBack,
    )
}
