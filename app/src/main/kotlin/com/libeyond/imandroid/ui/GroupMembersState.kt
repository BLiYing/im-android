package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.GroupMembersPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 群成员分页状态持有者（从 `GroupInfoHost` 拆出，CODING_STYLE §7①；那个文件贴着 600 行硬闸）。
 *
 * [refresh] 与 [loadNext] 共享同一份 `loading` 标志——避免深分页时"触底加载更多"与
 * "长按管理动作后刷新首页"并发写 `members`/`cursor`/`hasMore`，旧游标数据拼接出成员区间空洞
 * （`/code-review` 抓出，对齐 iOS 每个动作后都调 `loadGroupInfo` 的同一取舍）。
 */
internal class GroupMembersState(
    /** 拉一页（游标空=首页）；失败回 null，调用方保留旧数据。 */
    private val fetch: suspend (cursor: String) -> GroupMembersPage?,
    /** 首页到手后的副作用（喂 [RtcCall] 的名字/头像缓存）。 */
    private val onFirstPage: (List<GroupMember>) -> Unit = {},
) {
    constructor(client: IMClient, convId: String) : this(
        fetch = { cursor ->
            var got: GroupMembersPage? = null
            loadMore(client, convId, cursor) { got = it }
            got
        },
        onFirstPage = { RtcCall.onGroupMembers(convId, it) },
    )

    var members by mutableStateOf<List<GroupMember>>(emptyList())
        private set
    var cursor by mutableStateOf("")
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set

    /** 有人在 [loading] 期间要求刷新：在途那次的数据可能早于刚发生的写，必须再来一轮。 */
    private var dirty = false

    /**
     * 首页整页替换：初次进页、或任意写接口调完之后。服务端是权威，别本地猜新状态。
     *
     * **在途时不能直接丢弃请求**：在途那次可能发出于写操作之前（如撤销管理员的 `group` 帧触发的重拉），
     * 返回的是旧角色；丢掉本次等于让旧数据留在屏上（撤销最后一位管理员后他还挂在列表里）。
     * 故打 [dirty] 标记，由在途方收尾后再补一轮，直到没有新请求为止。
     */
    suspend fun refresh() {
        if (loading) {
            dirty = true
            return
        }
        do {
            dirty = false
            loading = true
            fetch("")?.let { page ->
                onFirstPage(page.items)
                members = page.items
                cursor = page.nextCursor
                hasMore = page.hasMore
            }
            loading = false
        } while (dirty)
    }

    /**
     * 触底续拉：在途守卫 + 按 userId 去重。滚到底自动触发时可能被连续调用两次，
     * 不守的话同一页会被追加两次（im-web 三周前踩过的形状，见 ../IMServer/docs/SYMMETRY.md）。
     */
    fun loadNext(scope: CoroutineScope) {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            fetch(cursor)?.let { page ->
                val existing = members.mapTo(HashSet()) { it.userId }
                members = members + page.items.filter { it.userId !in existing }
                cursor = page.nextCursor
                hasMore = page.hasMore
            }
            loading = false
            if (dirty) refresh() // 续拉期间有人要求刷新：续拉的游标已过期，整页重来
        }
    }
}

@Composable
internal fun rememberGroupMembersState(client: IMClient, convId: String): GroupMembersState =
    remember(convId) { GroupMembersState(client, convId) }
