package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.GroupMemberSearch
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.GroupMemberSearchScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 群成员搜索页接线层（从 `GroupInfoHost` 独立成一页，那个文件贴着 600 行硬闸）。
 *
 * 恒走服务端 `?q=`、去抖/翻页/去重口径见 [GroupMemberSearch]（判据整体照抄 iOS
 * `IMGroupMemberSearchViewController`）。去抖用 `LaunchedEffect` 换 key 自动取消上一次在途请求，
 * 不用像 iOS 那样手动记一个 search token 判过期——同 `rememberMentionComposer` 的写法。
 */
@Composable
fun GroupMemberSearchHost(
    client: IMClient,
    convId: String,
    totalMembers: Int,
    onPickMember: (GroupMember) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var cursor by remember { mutableStateOf("") }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // 结果对应的那个词，供续页使用——不是输入框里正在打的那个（可能已经变了）
    var loadedNeedle by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun fetch(q: String, pageCursor: String) {
        loading = true
        runCatchingCancellable {
            client.groups.members(convId, cursor = pageCursor, q = q, limit = GroupMemberSearch.PAGE_SIZE)
        }.onSuccess { page ->
            failed = false
            loadedNeedle = q
            results = GroupMemberSearch.mergePage(results, page.items, isFirstPage = pageCursor.isEmpty())
            cursor = page.nextCursor
            // has_more 为真但这页一个人都没回时也要停：否则服务端异常时永远点不完
            hasMore = page.hasMore && page.items.isNotEmpty()
        }.onFailure {
            // **保留上一次结果**，不清空成「没有匹配」——那会让用户以为查无此人，而不是网断了
            failed = true
            hasMore = false
            IMLog.tag("IM.Group").w("group_member_search_failed")
        }
        loading = false
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList(); cursor = ""; hasMore = false; failed = false
            return@LaunchedEffect
        }
        delay(GroupMemberSearch.DEBOUNCE_MS)
        fetch(query, "")
    }

    GroupMemberSearchScreen(
        totalMembers = totalMembers,
        query = query,
        onQueryChange = { query = it },
        results = results,
        hasMore = hasMore,
        loading = loading,
        failed = failed,
        onLoadMore = {
            if (!loading && hasMore && loadedNeedle.isNotEmpty()) scope.launch { fetch(loadedNeedle, cursor) }
        },
        onPickMember = onPickMember,
        onBack = onBack,
    )
}
