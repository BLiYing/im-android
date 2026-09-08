package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.GroupInfoNav
import com.libeyond.imandroid.data.GroupInfoPage
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.data.GroupSettings
import com.libeyond.imandroid.data.MemberProfile
import com.libeyond.imandroid.ui.screens.GroupManageAction
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.IMTextPrompt
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.JoinRequest
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.GroupInfoScreen
import com.libeyond.imandroid.ui.screens.GroupManageScreen
import com.libeyond.imandroid.ui.screens.JoinRequestsScreen
import kotlinx.coroutines.launch

/**
 * 群资料接线层。
 *
 * **超级群不物化成员表**：`GET /groups/{id}` 对超级群只回我自己，
 * 成员必须走分页接口 `GET /groups/{id}/members`（注意它的数组在 `items` 不是 `members`）。
 * 普通群两条都能用，这里统一走分页——省得为两种群写两套加载逻辑。
 */
@Composable
fun GroupInfoHost(
    client: IMClient,
    convId: String,
    /** 本地好友表（uid → 行）。成员资料页进页即用它定型关系与备注，避免闪动。 */
    knownFriends: Map<String, FriendEntry>,
    onOpenChat: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var info by remember(convId) { mutableStateOf<GroupInfo?>(null) }
    var members by remember(convId) { mutableStateOf<List<GroupMember>>(emptyList()) }
    var cursor by remember(convId) { mutableStateOf("") }
    var hasMore by remember(convId) { mutableStateOf(false) }
    var loading by remember(convId) { mutableStateOf(false) }

    // 点开的成员资料页盖在群资料之上；开着时本页的返回让位给它
    var memberProfile by remember(convId) { mutableStateOf<GroupMember?>(null) }
    // 待审入群申请（G3）。null = 没打开过
    var joinReqs by remember(convId) { mutableStateOf<List<JoinRequest>?>(null) }
    var joinReqsLoading by remember(convId) { mutableStateOf(false) }
    var deciding by remember(convId) { mutableStateOf("") }
    // 群管理二级页（仅群主/管理员能进）
    var managing by remember(convId) { mutableStateOf(false) }

    // 返回键**一处派发**，不再靠每个子页面自己记得接（一天漏了三次，见 GroupInfoPage 的注释）。
    // 枚举加一页，这个 when 就编译不过——漏不掉。
    val page = GroupInfoNav.current(
        joinRequestsOpen = joinReqs != null,
        memberProfileOpen = memberProfile != null,
        managing = managing,
    )
    BackHandler {
        when (page) {
            GroupInfoPage.JoinRequests -> joinReqs = null
            GroupInfoPage.MemberProfile -> memberProfile = null
            GroupInfoPage.Manage -> managing = false
            GroupInfoPage.Detail -> onBack()
        }
    }

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

    /** 拉全量（待处理 + 已处理）——只拉待处理的话，审批完列表会空掉，看着像没生效。 */
    suspend fun reloadJoinRequests() {
        runCatching { client.groups.joinRequests(convId) }
            .onSuccess { joinReqs = it }
            .onFailure { IMLog.tag("IM.Group").w("join_requests_failed") }
    }

    val reqs = joinReqs
    val mp = memberProfile
    // **四个页面互斥、且都不 return**：底下的编辑框/确认框/toast 要对每一页都生效。
    // 此前待审列表那条是 `return` 的，结果审批完的 toast 根本不显示——
    // 与「每加一个覆盖层都没人想起返回键」是同一类账：加页面时忘了页面之外还有东西要渲染。
    // 层级由深到浅：待审(从管理页进) > 成员资料(从详情进) > 管理页 > 详情。
    if (reqs != null) {
        JoinRequestsScreen(
            requests = reqs,
            loading = joinReqsLoading,
            busyUid = deciding,
            onDecide = { uid, approve ->
                deciding = uid
                scope.launch {
                    val r = runCatching { client.groups.reviewJoinRequest(convId, uid, approve) }
                    r.onFailure { e ->
                        val code = (e as? com.libeyond.imandroid.sdk.http.ApiException)?.code
                        toast = if (code != null) "操作失败（$code）" else "操作失败"
                    }
                    if (r.isSuccess) toast = if (approve) "已同意入群" else "已拒绝"
                    // 无论成败都重拉：失败可能是别人已经审过了，本地那条状态已经不对了
                    reloadJoinRequests()
                    // 顺带刷群资料，pending_count 角标要跟着掉
                    runCatching { client.groups.info(convId) }.onSuccess { info = it }
                    deciding = ""
                }
            },
            onBack = { joinReqs = null },
        )
    } else if (mp != null) {
        val f = knownFriends[mp.userId]
        UserProfileHost(
            client = client,
            userId = mp.userId,
            // 关系与种子的口径都在 MemberProfile 里（那两条坑写在它的注释上）
            knownRelation = MemberProfile.relationOf(mp.userId, myUid, f),
            seed = MemberProfile.seedOf(mp, f),
            onSendMessage = { card ->
                memberProfile = null
                onOpenChat(client.conversationStubFor(card.userId, card.displayName, card.avatarUrl))
            },
            onBack = { memberProfile = null },
        )
    } else if (managing) {
        GroupManageScreen(
            info = g,
            onManage = { action -> manage = action },
            onToggleSetting = { key ->
                // **整体替换**：五个值一次全传，翻转哪一个由纯函数算（见 GroupSettings）
                val v = GroupSettings.toggled(g, key)
                runManage(GroupSettings.label(key)) {
                    client.groups.updateSettings(
                        convId,
                        joinApproval = v.joinApproval,
                        permInvite = v.permInvite,
                        permEditInfo = v.permEditInfo,
                        permPin = v.permPin,
                        historyVisible = v.historyVisible,
                    )
                }
            },
            onOpenJoinRequests = {
                joinReqs = emptyList()
                joinReqsLoading = true
                scope.launch { reloadJoinRequests(); joinReqsLoading = false }
            },
            onBack = { managing = false },
        )
    } else {
        // **整页替换而不是叠一层**：`GroupInfoHost` 的内容不在自己的 Box 里，
        // 父布局是谁由调用方决定，叠出来可能是竖排而不是覆盖。替换还顺带让
        // 详情页的滚动位置与成员分页游标原样留着（那些 remember 都在上面，没被跳过）。
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
        onOpenMember = { m -> memberProfile = m },
        myUid = client.uid.orEmpty(),
        onOpenManage = { managing = true },
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
    }

    // —— 管理项的编辑框（对四个页面都生效）——
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
