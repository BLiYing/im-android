package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.screens.UserSearchScreen
import kotlinx.coroutines.launch

/**
 * 「添加朋友」页的状态与动作。**两个入口共用**：通讯录右上角、消息页 ＋ 菜单
 * （iOS 两处 push 的都是同一个 `IMUserSearchViewController`）。此前状态长在 ContactsHost 里，
 * 消息页要进同一页只能再抄一份。
 *
 * @param onChanged 发出申请 / 同意之后回调——通讯录据此重拉好友表。
 */
@Composable
fun AddFriendHost(
    client: IMClient,
    onOpenChat: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserCard>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    /** uid → 与我的关系：决定结果行的按钮是「加好友 / 已申请 / 同意 / 发消息」哪一个。 */
    var relations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    suspend fun reloadRelations() {
        runCatchingCancellable { client.contacts.friends() }
            .onSuccess { list -> relations = list.associate { it.userId to it.status } }
    }
    LaunchedEffect(Unit) { reloadRelations() }

    UserSearchScreen(
        query = query,
        onQueryChange = { query = it },
        onSearch = {
            scope.launch {
                searching = true; error = ""
                try {
                    results = client.contacts.search(query.trim())
                    searched = true
                } catch (e: ApiException) {
                    error = if (e.isTransport) Str.s(R.string.net_error_generic) else e.message
                } finally { searching = false }
            }
        },
        results = results,
        relations = relations,
        searching = searching,
        searched = searched,
        error = error,
        onAdd = { u ->
            scope.launch {
                runCatchingCancellable {
                    // 已是对方的待确认申请 → 同意；否则发起申请
                    if (relations[u.userId] == FriendEntry.PENDING) client.contacts.accept(u.userId)
                    else client.contacts.request(u.userId)
                }
                reloadRelations()
                onChanged()
            }
        },
        onOpenChat = { u -> onOpenChat(client.conversationStubFor(u.userId, u.displayName, u.avatarUrl)) },
        onBack = onBack,
    )
}
