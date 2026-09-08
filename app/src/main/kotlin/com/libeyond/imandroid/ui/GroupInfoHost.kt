package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.GroupInfoNav
import com.libeyond.imandroid.data.GroupInfoPage
import com.libeyond.imandroid.data.PickPurpose
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailActions
import com.libeyond.imandroid.data.DetailMoreAction
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
import com.libeyond.imandroid.sdk.api.GroupBan
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.JoinRequest
import com.libeyond.imandroid.data.db.ConversationEntity
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.GroupAdminListScreen
import com.libeyond.imandroid.ui.screens.GroupBanListScreen
import com.libeyond.imandroid.ui.screens.GroupInfoScreen
import com.libeyond.imandroid.ui.screens.PickListScreen
import com.libeyond.imandroid.ui.screens.PickRow
import com.libeyond.imandroid.ui.screens.GroupManageScreen
import com.libeyond.imandroid.ui.screens.JoinRequestsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** 「搜索」pill：关掉本页、回聊天页开搜索态（本页盖在聊天页之上，只能这么绕）。 */
    onSearchInChat: () -> Unit = {},
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
    // 会话媒体归档（详情页的「聊天媒体」，与单聊那侧同一个组件）
    var mediaOpen by remember(convId) { mutableStateOf(false) }
    // 治理三页 + 一个通用选人页
    var bans by remember(convId) { mutableStateOf<List<GroupBan>?>(null) }
    var bansLoading by remember(convId) { mutableStateOf(false) }
    var adminsOpen by remember(convId) { mutableStateOf(false) }
    var pick by remember(convId) { mutableStateOf<PickPurpose?>(null) }
    var picked by remember(convId) { mutableStateOf<Set<String>>(emptySet()) }
    var friends by remember(convId) { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var confirmTransfer by remember(convId) { mutableStateOf<GroupMember?>(null) }
    // 头部操作排「更多」里那几件要二次确认的事（清空/退群/解散）
    var confirmMore by remember(convId) { mutableStateOf<DetailMoreAction?>(null) }

    // 返回键**一处派发**，不再靠每个子页面自己记得接（一天漏了三次，见 GroupInfoPage 的注释）。
    // 枚举加一页，这个 when 就编译不过——漏不掉。
    val page = GroupInfoNav.current(
        pickOpen = pick != null,
        bansOpen = bans != null,
        adminsOpen = adminsOpen,
        joinRequestsOpen = joinReqs != null,
        memberProfileOpen = memberProfile != null,
        mediaOpen = mediaOpen,
        managing = managing,
    )
    BackHandler {
        when (page) {
            GroupInfoPage.Pick -> { pick = null; picked = emptySet() }
            GroupInfoPage.Bans -> bans = null
            GroupInfoPage.Admins -> adminsOpen = false
            GroupInfoPage.JoinRequests -> joinReqs = null
            GroupInfoPage.MemberProfile -> memberProfile = null
            GroupInfoPage.Media -> mediaOpen = false
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

    // 换群头像：选图 → 压成 JPEG → POST /avatar → PUT /groups/{id}（**整体替换**：
    // 名字与简介必须原样带回，否则会被清空，见 PROTOCOL §11）。
    // 与「我的资料」那侧的差别是**这里立刻提交**：群管理页没有「保存」按钮，
    // 每一项都是即时生效的，头像若只更新预览就成了唯一一个"改了但没生效"的项。
    val pickGroupAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val g0 = info
        if (uri == null || g0 == null) return@rememberLauncherForActivityResult
        scope.launch {
            toast = "上传中…"
            val bytes = withContext(Dispatchers.IO) { AvatarPrepare.fromUri(context, uri) }
            if (bytes == null) { toast = "图片处理失败，换一张试试"; return@launch }
            val up = runCatching { client.upload.uploadAvatar(bytes) }
            val url = up.getOrNull()?.url
            if (url == null) {
                toast = "头像上传失败"
                IMLog.tag("IM.Group").w("group_avatar_upload_failed")
                return@launch
            }
            runManage("修改群头像") { client.groups.updateInfo(convId, g0.name, url, g0.intro) }
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
    // **每页各自记住自己的滚动位置**。本页用"整页替换"做导航，切页时旧页整个离开组合，
    // 没有 SaveableStateHolder 的话 rememberScrollState / LazyListState 全部丢失——
    // 表现是：从 2000 人成员列表点进一个人，返回后弹回列表顶部。
    // iOS 的 push/pop 天然保住这些，本端得自己兜。
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

    val pk = pick
    stateHolder.SaveableStateProvider(page) {
    if (pk != null) {
        // 三种用途共用一个选择页（见 PickListScreen 的注释）：为每种单写一个，
        // 最后必然在「已选计数」「上限截断」「空态文案」上各写各的。
        val rows = when (pk) {
            PickPurpose.AddAdmin -> members
                .filter { it.role == GroupMember.ROLE_MEMBER }
                .map { PickRow(it.userId, it.displayName, it.avatarUrl, it.handle) }
            PickPurpose.Transfer -> members
                .filter { it.userId != myUid }
                .map { PickRow(it.userId, it.displayName, it.avatarUrl, it.handle) }
            PickPurpose.Invite -> {
                val inGroup = members.mapTo(HashSet()) { it.userId }
                friends.filter { it.userId !in inGroup }
                    .map { PickRow(it.userId, it.displayName, it.avatarUrl, it.handle) }
            }
        }
        PickListScreen(
            title = when (pk) {
                PickPurpose.AddAdmin -> "添加管理员"
                PickPurpose.Transfer -> "选择新群主"
                PickPurpose.Invite -> "邀请入群"
            },
            rows = rows,
            selected = picked,
            multi = pk == PickPurpose.Invite,
            emptyText = when (pk) {
                PickPurpose.AddAdmin -> "没有可设为管理员的普通成员"
                PickPurpose.Transfer -> "群里还没有别人"
                PickPurpose.Invite -> "好友都已在群里"
            },
            onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
            onPick = { row ->
                when (pk) {
                    // 设管理员是可撤销的，直接做；转让不可逆，先二次确认
                    PickPurpose.AddAdmin -> {
                        pick = null
                        runManage("设为管理员") { client.groups.setRole(convId, row.id, GroupMember.ROLE_ADMIN) }
                    }
                    PickPurpose.Transfer -> confirmTransfer = members.firstOrNull { it.userId == row.id }
                    PickPurpose.Invite -> Unit
                }
            },
            onConfirm = {
                val ids = picked.toList()
                pick = null
                picked = emptySet()
                if (ids.isNotEmpty()) runManage("邀请入群") { client.groups.invite(convId, ids) }
            },
            onBack = { pick = null; picked = emptySet() },
        )
    } else if (bans != null) {
        GroupBanListScreen(
            bans = bans.orEmpty(),
            loading = bansLoading,
            busyUid = deciding,
            onUnban = { uid ->
                deciding = uid
                scope.launch {
                    val r = runCatching { client.groups.unban(convId, uid) }
                    toast = if (r.isSuccess) "已解除" else "解除失败"
                    runCatching { client.groups.bans(convId) }.onSuccess { bans = it }
                    deciding = ""
                }
            },
            onBack = { bans = null },
        )
    } else if (adminsOpen) {
        GroupAdminListScreen(
            admins = members.filter { it.role == GroupMember.ROLE_ADMIN },
            // 群主可增删、管理员只读（同 im-web）
            canEdit = g.myRole == GroupMember.ROLE_OWNER,
            busyUid = deciding,
            onRevoke = { m ->
                deciding = m.userId
                scope.launch {
                    val r = runCatching { client.groups.setRole(convId, m.userId, GroupMember.ROLE_MEMBER) }
                    toast = if (r.isSuccess) "已撤销" else "撤销失败"
                    // 撤销后重拉首页成员——角色变了，管理员列表要跟着变
                    loadMore(client, convId, "") { pg ->
                        members = pg.items
                        cursor = pg.nextCursor
                        hasMore = pg.hasMore
                    }
                    deciding = ""
                }
            },
            onAdd = { pick = PickPurpose.AddAdmin },
            onBack = { adminsOpen = false },
        )
    } else if (reqs != null) {
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
    } else if (mediaOpen) {
        // 与单聊详情用的是同一个 ConvMediaHost —— 归档这件事在群聊和单聊里
        // 完全一样（同一个接口、同一套分页、同一个查看器），没有分两份的理由
        ConvMediaHost(
            client = client,
            convId = convId,
            isGroup = true,
            onBack = { mediaOpen = false },
        )
    } else if (managing) {
        GroupManageScreen(
            info = g,
            banCount = bans?.size,
            members = members,
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
            onOpenBans = {
                bans = emptyList()
                bansLoading = true
                scope.launch {
                    runCatching { client.groups.bans(convId) }
                        .onSuccess { bans = it }
                        .onFailure { IMLog.tag("IM.Group").w("group_bans_failed") }
                    bansLoading = false
                }
            },
            onOpenAdmins = { adminsOpen = true },
            onTransferOwner = { pick = PickPurpose.Transfer },
            // 与「群名称/群简介」同一份判据——权限分叉了就会出现"相机圈亮着，点了报 300204"
            onPickAvatar = if (GroupPermissions.canEditInfo(g)) {
                { pickGroupAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            } else {
                null
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
            onOpenMedia = { mediaOpen = true },
            onInvite = {
                pick = PickPurpose.Invite
                picked = emptySet()
                scope.launch {
                    runCatching { client.contacts.friends() }
                        .onSuccess { list -> friends = list.filter { it.status == FriendEntry.ACCEPTED } }
                        .onFailure { toast = "好友列表加载失败" }
                }
            },
        actions = DetailActions.pillsFor(
            isGroup = true, isSystemPeer = false, peerIsFriend = false, showsMessagePill = false,
        ),
        moreItems = DetailActions.moreFor(
            isGroup = true, isSystemPeer = false,
            iAmOwner = g.myRole == GroupMember.ROLE_OWNER,
            peerBlocked = false, peerIsFriend = false,
        ),
        onAction = { a ->
            // 群这一侧 pills 只有「搜索 / 更多」，更多由 onMore 走
            if (a == DetailAction.Search) onSearchInChat()
        },
        onMore = { m -> confirmMore = m },
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
    }

    // —— 头部「更多」的二次确认（对每一页都生效；文案在 GroupInfoDialogs.kt）——
    GroupMoreConfirmDialog(
        action = confirmMore,
        groupName = g.name,
        onDismiss = { confirmMore = null },
        onClearHistory = {
            confirmMore = null
            scope.launch {
                // **只删本机**（同 iOS）：服务端没有"替所有人删历史"的接口
                client.repo.clearConversation(client.uid.orEmpty(), convId)
                toast = "聊天记录已清空"
            }
        },
        onLeave = {
            confirmMore = null
            scope.launch {
                runCatching { client.groups.leave(convId) }
                    .onFailure { toast = it.userMessage("退出失败"); return@launch }
                client.messages.refreshConversations()
                onLeft()
            }
        },
        onDissolve = {
            confirmMore = null
            scope.launch {
                runCatching { client.groups.dissolve(convId) }
                    .onFailure { toast = it.userMessage("解散失败"); return@launch }
                client.messages.refreshConversations()
                onLeft()
            }
        },
    )

    // —— 管理项的编辑框（对每一页都生效）——
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

    confirmTransfer?.let { m ->
        IMConfirmDialog(
            title = "转让群组",
            message = "转让给「${m.displayName}」后你将立即变为普通成员，且不可撤销。",
            confirmText = "转让",
            destructive = true,
            onDismiss = { confirmTransfer = null },
            onConfirm = {
                confirmTransfer = null
                pick = null
                runManage("转让群组") { client.groups.transferOwner(convId, m.userId) }
            },
        )
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
