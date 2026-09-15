package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.ui.screens.CreateGroupScreen
import kotlinx.coroutines.launch

/**
 * 建群页的状态与动作。**两个入口共用**：通讯录「群聊」列表右上角、消息页 ＋ 菜单
 * （iOS 两处走的也是同一份 `IMGroupCreateViewController`，其注释记着此前两处各有一份实现的教训）。
 *
 * @param seedFriends 调用方手上已有的好友表，先用它画，进页再拉一次新的——不然从消息页进来
 *                    （主界面那份好友表只在登录时拉过一次）会漏掉之后才加的好友。
 * @param onCreated 建成之后调用方决定去哪：通讯录回列表，消息页直接进新群（同 iOS `startNewGroup`）。
 */
@Composable
fun CreateGroupHost(
    client: IMClient,
    seedFriends: List<FriendEntry>,
    onCreated: (GroupInfo) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf(seedFriends.filter { it.status == FriendEntry.ACCEPTED }) }
    var name by remember { mutableStateOf("") }
    var picks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // **群上限读服务端配置，不硬编码**（要装更多人走大群，不是调大这个数）
    var maxMembers by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        runCatchingCancellable { client.conversationsApi.serverConfig().maxGroupMembers }
            .onSuccess { maxMembers = it }
        runCatchingCancellable { client.contacts.friends() }
            .onSuccess { list -> friends = list.filter { it.status == FriendEntry.ACCEPTED } }
    }

    CreateGroupScreen(
        name = name,
        onNameChange = { name = it },
        friends = friends,
        selected = picks,
        onToggle = { id -> picks = if (id in picks) picks - id else picks + id },
        maxMembers = maxMembers,
        busy = creating,
        error = error,
        onCreate = {
            scope.launch {
                creating = true; error = ""
                try {
                    val group = client.groups.create(name.trim(), picks.toList())
                    client.messages.refreshConversations()
                    onCreated(group)
                } catch (e: ApiException) {
                    error = if (e.isTransport) "网络请求失败" else e.message
                } finally { creating = false }
            }
        },
        onBack = onBack,
    )
}
