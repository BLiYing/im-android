package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * `IMGroupMemberSearchViewController`）。去抖用 `LaunchedEffect` 换 key 自动取消上一次在途的
 * **首页**请求，但**续页**请求跑在 `rememberCoroutineScope()` 上、不受 `query` 变化牵连
 * ——旧词还没翻完页时用户又打了新字，旧词那页迟到的响应会把新词的结果悄悄拼污染。
 * 故仍需要 iOS 那枚 `_searchToken` 的等价物：[searchGen]，每次 `query` 变化自增，
 * 响应落地时核对代次不对就整批丢弃（`/code-review` 抓出，同一坑 iOS 早就防了）。
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
    // 每次 query 变化自增一代（同 iOS `_searchToken`）：续页请求带着发起时的代次出去，
    // 响应回来时代次对不上就整批丢弃——旧词的迟到分页不会污染新词已经显示的结果。
    var searchGen by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun fetch(q: String, pageCursor: String, gen: Int) {
        runCatchingCancellable {
            client.groups.members(convId, cursor = pageCursor, q = q, limit = GroupMemberSearch.PAGE_SIZE)
        }.onSuccess { page ->
            if (gen != searchGen) return@onSuccess // 过期响应（词已经变了），丢弃
            failed = false
            loadedNeedle = q
            results = GroupMemberSearch.mergePage(results, page.items, isFirstPage = pageCursor.isEmpty())
            cursor = page.nextCursor
            // has_more 为真但这页一个人都没回时也要停：否则服务端异常时永远点不完
            hasMore = page.hasMore && page.items.isNotEmpty()
        }.onFailure {
            if (gen != searchGen) return@onFailure
            // **保留上一次结果**，不清空成「没有匹配」——那会让用户以为查无此人，而不是网断了
            failed = true
            hasMore = false
            IMLog.tag("IM.Group").w("group_member_search_failed")
        }
        if (gen == searchGen) loading = false
    }

    LaunchedEffect(query) {
        searchGen++
        val gen = searchGen
        if (query.isBlank()) {
            results = emptyList(); cursor = ""; hasMore = false; failed = false; loading = false
            return@LaunchedEffect
        }
        delay(GroupMemberSearch.DEBOUNCE_MS)
        loading = true
        fetch(query, "", gen)
    }

    // **在途守卫同步设在 launch 之前**（不是 fetch() 内部）：LazyColumn 里逼近底部的每一行各自
    // 挂一个 LaunchedEffect 调用它，同一帧可能连续调用好几次——loading 要是等协程真正开始跑
    // 才置 true（异步），这几次调用会一起穿过 `!loading` 检查，打出好几发重复分页请求
    // （`/code-review` 抓出，同 `GroupMembersState.loadNext` 已经在防的那类竞态）。
    fun requestLoadMore() {
        if (loading || !hasMore || loadedNeedle.isEmpty()) return
        loading = true
        val gen = searchGen
        scope.launch { fetch(loadedNeedle, cursor, gen) }
    }

    GroupMemberSearchScreen(
        totalMembers = totalMembers,
        query = query,
        onQueryChange = { query = it },
        results = results,
        hasMore = hasMore,
        loading = loading,
        failed = failed,
        onLoadMore = ::requestLoadMore,
        onPickMember = onPickMember,
        onBack = onBack,
    )
}
