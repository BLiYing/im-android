package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.ui.screens.GroupManageAction
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.IMTextPrompt
import com.libeyond.imandroid.ui.components.IMConfirmDialog
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

    // —— 群管理（G1/G2）——
    var manage by remember(convId) { mutableStateOf<GroupManageAction?>(null) }
    var memberMenu by remember(convId) { mutableStateOf<GroupMember?>(null) }
    var toast by remember(convId) { mutableStateOf<String?>(null) }
    val myUid = client.uid.orEmpty()

    /** 调完写接口统一刷一次群资料——服务端是权威，别本地猜新状态。 */
    fun runManage(label: String, block: suspend () -> Unit) {
        scope.launch {
            val r = runCatching { block() }
            r.onFailure { e ->
                val code = (e as? com.libeyond.imandroid.sdk.http.ApiException)?.code
                // 端上放行了但服务端拒——把码带出来，别只说「失败」
                toast = if (code != null) "${label}失败（$code）" else "${label}失败"
                IMLog.tag("IM.Group").w("group_manage_failed", "op" to label, "code" to (code ?: -1))
            }
            if (r.isSuccess) toast = "${label}成功"
            runCatching { client.groups.info(convId) }.onSuccess { info = it }
        }
    }

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
        myUid = client.uid.orEmpty(),
        onManage = { action -> manage = action },
        onMemberLongPress = { m -> memberMenu = m },
        onLeave = {
            scope.launch {
                runCatching { client.groups.leave(convId) }
                client.messages.refreshConversations()
                onLeft()
            }
        },
        onBack = onBack,
    )

    // —— 管理项的编辑框 ——
    when (manage) {
        GroupManageAction.EditName -> IMTextPrompt(
            title = "群名称", initial = g.name, maxLen = 30,
            onDismiss = { manage = null },
            onConfirm = { v ->
                manage = null
                // 改群资料是**整体替换**：只改名字也要把头像和简介原样带回去，
                // 否则会把它们清空（PROTOCOL §11 明说整体替换）。
                runManage("修改群名") { client.groups.updateInfo(convId, v, g.avatarUrl, g.intro) }
            },
        )
        GroupManageAction.EditIntro -> IMTextPrompt(
            title = "群简介", initial = g.intro, maxLen = 200, multiline = true,
            onDismiss = { manage = null },
            onConfirm = { v ->
                manage = null
                runManage("修改群简介") { client.groups.updateInfo(convId, g.name, g.avatarUrl, v) }
            },
        )
        GroupManageAction.EditAnnouncement -> IMTextPrompt(
            title = "群公告", initial = g.announcement, maxLen = 500, multiline = true,
            hint = "留空即撤下公告。发布会在群里落一条系统消息。",
            onDismiss = { manage = null },
            onConfirm = { v ->
                manage = null
                runManage(if (v.isBlank()) "撤下公告" else "发布公告") {
                    client.groups.setAnnouncement(convId, v)
                }
            },
        )
        GroupManageAction.ToggleMuteAll -> {
            val on = GroupPermissions.isMuteActive(g.muteUntil)
            IMConfirmDialog(
                title = if (on) "解除全员禁言？" else "开启全员禁言？",
                message = if (on) "解除后所有成员都可以发言。"
                else "开启后只有群主和管理员可以发言，直到你手动解除。",
                confirmText = if (on) "解除" else "开启",
                destructive = !on,
                onDismiss = { manage = null },
                onConfirm = {
                    manage = null
                    // -1 = 永久（协议口径），0 = 解除
                    runManage(if (on) "解除全员禁言" else "开启全员禁言") {
                        client.groups.setMuteAll(convId, if (on) 0L else -1L)
                    }
                },
            )
        }
        null -> Unit
    }

    // —— 成员长按菜单 ——
    memberMenu?.let { m ->
        val actions = buildList {
            if (GroupPermissions.canSetRole(g, m, myUid)) {
                val makeAdmin = !m.isAdmin
                add(SheetItem(if (makeAdmin) "设为管理员" else "撤销管理员") {
                    runManage(if (makeAdmin) "设为管理员" else "撤销管理员") {
                        client.groups.setRole(convId, m.userId, if (makeAdmin) "admin" else "member")
                    }
                })
            }
            if (GroupPermissions.canMute(g, m, myUid)) {
                val muted = GroupPermissions.isMuteActive(m.muteUntil)
                add(SheetItem(if (muted) "解除禁言" else "禁言") {
                    runManage(if (muted) "解除禁言" else "禁言") {
                        client.groups.muteMember(convId, m.userId, if (muted) 0L else -1L)
                    }
                })
            }
            if (GroupPermissions.canTransfer(g, m, myUid)) {
                add(SheetItem("转让群主", destructive = true) {
                    runManage("转让群主") { client.groups.transferOwner(convId, m.userId) }
                })
            }
            if (GroupPermissions.canRemove(g, m, myUid)) {
                add(SheetItem("移出群聊", destructive = true) {
                    // 缺省 cooldown=24h：只移出（none）会让人立刻又进来，
                    // 永久黑名单（forever）对一次误操作又太重（PROTOCOL §11 三档）
                    runManage("移出群聊") { client.groups.removeMember(convId, m.userId, ban = "cooldown") }
                })
            }
        }
        if (actions.isEmpty()) {
            memberMenu = null
        } else {
            ActionSheet(
                title = m.displayName,
                items = actions,
                onDismiss = { memberMenu = null },
            )
        }
    }

    toast?.let { t -> IMToast(t) { toast = null } }
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
