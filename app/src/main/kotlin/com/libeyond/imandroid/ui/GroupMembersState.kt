package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupMember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 群成员分页状态持有者（从 `GroupInfoHost` 拆出，CODING_STYLE §7①；那个文件贴着 600 行硬闸）。
 *
 * [refresh] 与 [loadNext] 共享同一份 `loading` 标志——避免深分页时"触底加载更多"与
 * "长按管理动作后刷新首页"并发写 `members`/`cursor`/`hasMore`，旧游标数据拼接出成员区间空洞
 * （`/code-review` 抓出，对齐 iOS 每个动作后都调 `loadGroupInfo` 的同一取舍）。
 */
internal class GroupMembersState(private val client: IMClient, private val convId: String) {
    var members by mutableStateOf<List<GroupMember>>(emptyList())
        private set
    var cursor by mutableStateOf("")
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set

    /**
     * 首页整页替换：初次进页、或任意写接口调完之后。服务端是权威，别本地猜新状态。
     * 顺手喂给 [RtcCall]（群通话按这份表取名字与头像）——单纯的缓存写入，任何刷新场景下都该做。
     */
    suspend fun refresh() {
        if (loading) return
        loading = true
        loadMore(client, convId, "") { page ->
            RtcCall.onGroupMembers(convId, page.items)
            members = page.items
            cursor = page.nextCursor
            hasMore = page.hasMore
        }
        loading = false
    }

    /**
     * 触底续拉：在途守卫 + 按 userId 去重。滚到底自动触发时可能被连续调用两次，
     * 不守的话同一页会被追加两次（im-web 三周前踩过的形状，见 ../IMServer/docs/SYMMETRY.md）。
     */
    fun loadNext(scope: CoroutineScope) {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            loadMore(client, convId, cursor) { page ->
                val existing = members.mapTo(HashSet()) { it.userId }
                members = members + page.items.filter { it.userId !in existing }
                cursor = page.nextCursor
                hasMore = page.hasMore
            }
            loading = false
        }
    }
}

@Composable
internal fun rememberGroupMembersState(client: IMClient, convId: String): GroupMembersState =
    remember(convId) { GroupMembersState(client, convId) }
