package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.GroupInfoScreen
import kotlinx.coroutines.launch

/**
 * 群资料接线层。
 *
 * **超级群不物化成员表**：`GET /groups/{id}` 对超级群只回我自己，
 * 成员必须走分页接口 `GET /groups/{id}/members`（注意它的数组在 `items` 不是 `members`）。
 * 普通群两条都能用，这里统一走分页——省得为两种群写两套加载逻辑。
 */
@Composable
fun GroupInfoHost(client: IMClient, convId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember(convId) { mutableStateOf<GroupInfo?>(null) }
    var members by remember(convId) { mutableStateOf<List<GroupMember>>(emptyList()) }
    var cursor by remember(convId) { mutableStateOf("") }
    var hasMore by remember(convId) { mutableStateOf(false) }
    var loading by remember(convId) { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(convId) {
        runCatching { info = client.groups.info(convId) }
            .onFailure { IMLog.tag("IM.Group").w("group_info_failed") }
        loadMore(client, convId, cursor) { page ->
            members = page.items
            cursor = page.nextCursor
            hasMore = page.hasMore
        }
    }

    val g = info ?: return

    GroupInfoScreen(
        info = g,
        members = members,
        hasMoreMembers = hasMore,
        onLoadMoreMembers = {
            // 在途守卫：滚到底会连续触发，不守的话同一页会被追加两次——
            // im-web 三周前那条「连点加载更多把同一页追加两次」就是这个形状，
            // 而 iOS 后来在别处又演了一遍（见 ../IMServer/docs/SYMMETRY.md）
            if (!loading && hasMore) {
                loading = true
                scope.launch {
                    loadMore(client, convId, cursor) { page ->
                        // 按 userId 去重再追加——即便守卫被绕过也不会出现重复行
                        val existing = members.mapTo(HashSet()) { it.userId }
                        members = members + page.items.filter { it.userId !in existing }
                        cursor = page.nextCursor
                        hasMore = page.hasMore
                    }
                    loading = false
                }
            }
        },
        onOpenMember = { /* TODO(P13)：成员资料页 */ },
        onLeave = {
            scope.launch {
                runCatching { client.groups.leave(convId) }
                client.messages.refreshConversations()
                onLeft()
            }
        },
        onBack = onBack,
    )
}

private suspend inline fun loadMore(
    client: IMClient,
    convId: String,
    cursor: String,
    onPage: (com.libeyond.imandroid.sdk.api.GroupMembersPage) -> Unit,
) {
    runCatching { client.groups.members(convId, cursor) }
        .onSuccess(onPage)
        .onFailure { IMLog.tag("IM.Group").w("group_members_failed") }
}
