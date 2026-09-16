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
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.sdk.api.ConvMediaItem
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
    /** 归档长按「定位到聊天」：同上，关掉本页、把 conv_seq 交给聊天页。 */
    onLocateInChat: (Long) -> Unit = {},
    /** 进来先落在哪个页签。聊天页查看器的「媒体」钮要直达媒体页签（群资料默认是「成员」）。 */
    initialTab: DetailTab = DetailTab.Members,
    /** 只当会话媒体库用（查看器右下角「媒体」钮进的那一页）。见 `ChatDetailScreen.galleryOnly`。 */
    galleryOnly: Boolean = false,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 「链接」页签点一条在 App 内打开（宿主 WebLinkHost，iOS `openLink:`）
    val openLink = com.libeyond.imandroid.ui.components.LocalOpenLink.current
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
    // 归档已并进内联页签（2026-09-09），只剩「点开一张图/视频」还是独立的一层
    var tab by remember(convId) { mutableStateOf(initialTab) }
    val archive = rememberConvArchive(client, convId, tab)
    val linkMessages = rememberLinkMessages(client, convId)
    var viewing by remember(convId) { mutableStateOf<ConvMediaItem?>(null) }
    var archiveMenuFor by remember(convId) { mutableStateOf<ArchiveTarget?>(null) }
    var archiveMenuAnchor by remember(convId) { mutableStateOf(Rect.Zero) }
    /** 归档要转发的那一项（长按菜单与查看器「更多」共用这一份状态，见 ArchiveActionsHost 的注释）。 */
    var archiveForward by remember(convId) { mutableStateOf<ArchiveTarget?>(null) }
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
        mediaOpen = viewing != null,
        managing = managing,
    )
    BackHandler {
        when (page) {
            GroupInfoPage.Pick -> { pick = null; picked = emptySet() }
            GroupInfoPage.Bans -> bans = null
            GroupInfoPage.Admins -> adminsOpen = false
            GroupInfoPage.JoinRequests -> joinReqs = null
            GroupInfoPage.MemberProfile -> memberProfile = null
            GroupInfoPage.Media -> viewing = null
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
    val saveMedia = rememberMediaSaver { toast = it }
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
    } else if (viewing != null) {
        val m = viewing!!
        // 翻页 / 「更多」/ 转发都在 ArchiveViewer.kt 里，与单聊详情共用
        ArchiveMediaViewer(
            client = client, convId = convId, isGroup = true,
            iAmManager = info?.iAmManager == true,
            archive = archive, current = m, scope = scope,
            onSave = saveMedia,
            onForwardPicker = { archiveForward = it },
            onLocateInChat = { seq -> viewing = null; onLocateInChat(seq) },
            onChanged = { archive.reload() },
            onToast = { toast = it },
            onClose = { viewing = null },
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
            tab = tab,
            onTabChange = { tab = it },
            archive = archive.items,
            linkMessages = linkMessages,
            archiveLoading = archive.loading,
            archiveHasMore = archive.hasMore,
            onLoadMoreArchive = { archive.loadMore() },
            onOpenArchive = { item ->
                openArchiveItem(client, context, item, onToast = { toast = it }) { viewing = it }
            },
            onLongPressArchive = { t, r -> archiveMenuFor = t; archiveMenuAnchor = r },
            onOpenLink = { url -> openLink?.invoke(url) },
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            galleryOnly = galleryOnly,
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

    // —— 管理项的编辑框（对每一页都生效；实现在 GroupInfoDialogs.kt）——
    GroupManagePrompts(
        action = manage,
        info = g,
        convId = convId,
        client = client,
        onDismiss = { manage = null },
        runManage = ::runManage,
    )

    // —— 成员长按菜单 ——（判据与拼装在 GroupMemberMenu.kt）
    memberMenu?.let { m ->
        GroupMemberMenu(
            member = m,
            info = g,
            myUid = myUid,
            client = client,
            convId = convId,
            runManage = ::runManage,
            onDismiss = { memberMenu = null },
        )
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

    // 归档长按菜单 + 转发选择页（与单聊详情共用同一份接线）
    ArchiveActionsHost(
        client = client,
        convId = convId,
        isGroup = true,
        iAmManager = info?.iAmManager == true,
        target = archiveMenuFor,
        anchor = archiveMenuAnchor,
        onLocateInChat = { seq -> archiveMenuFor = null; onLocateInChat(seq) },
        onChanged = { archive.reload() },
        onToast = { toast = it },
        onForwardPicker = { archiveForward = it },
        onDismiss = { archiveMenuFor = null },
    )

    // 归档转发选择页（长按菜单与查看器「更多」共用）
    archiveForward?.let { t ->
        ArchiveForwardPicker(
            client = client, convId = convId, target = t, scope = scope,
            onDismiss = { archiveForward = null },
            onToast = { toast = it },
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
