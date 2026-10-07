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
import com.libeyond.imandroid.data.PickPurpose
import com.libeyond.imandroid.data.GroupPick
import com.libeyond.imandroid.rtc.RtcCall
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.data.DetailActions
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.data.GroupSettings
import com.libeyond.imandroid.data.groupVoiceSenderNameOf
import com.libeyond.imandroid.ui.screens.GroupManageAction
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.data.GroupInfoCache
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupBan
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.JoinRequest
import com.libeyond.imandroid.data.db.ConversationEntity
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.GroupAdminListScreen
import com.libeyond.imandroid.ui.screens.GroupBanListScreen
import com.libeyond.imandroid.ui.screens.GroupInfoScreen
import com.libeyond.imandroid.ui.screens.GroupManageScreen
import com.libeyond.imandroid.ui.screens.JoinRequestsScreen
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.components.ProvideMenuSurface
import com.libeyond.imandroid.ui.components.menuBackdrop
import com.libeyond.imandroid.ui.components.rememberMenuSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
    /** 「搜索」pill：关掉本页、回聊天页开搜索态（本页盖在聊天页之上，只能这么绕）。 */
    onSearchInChat: () -> Unit = {},
    /** 归档长按「定位到聊天」：同上，关掉本页、把 conv_seq 交给聊天页。 */
    onLocateInChat: (Long) -> Unit = {},
    /** 进来先落在哪个页签。聊天页查看器的「媒体」钮要直达媒体页签（群资料默认是「成员」）。 */
    initialTab: DetailTab = DetailTab.Members,
    /** 只当会话媒体库用（查看器右下角「媒体」钮进的那一页）。见 `ChatDetailScreen.galleryOnly`。 */
    galleryOnly: Boolean = false,
    /** 没有本地快照时的占位（会话快照里的群名 / 头像），**不当真资料用**，见 `GroupInfoPlaceholder`。 */
    seed: GroupInfo? = null,
    openJoinRequests: Boolean = false, // 直接落在「入群申请」列表（审批横幅点入，iOS 同）
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 「链接」页签点一条在 App 内打开（宿主 WebLinkHost，iOS `openLink:`）
    val openLink = com.libeyond.imandroid.ui.components.LocalOpenLink.current
    // 本地快照（上次拉到的群资料）：进页即画整页，网络回来再静默覆盖；没有快照才退到占位页
    val infoCache = remember(context) { GroupInfoCache(java.io.File(context.filesDir, "group_info")) }
    var info by remember(convId) { mutableStateOf<GroupInfo?>(infoCache.load(client.uid.orEmpty(), convId)) }
    var infoFailed by remember(convId) { mutableStateOf(false) }
    var infoReload by remember(convId) { mutableStateOf(0) }
    val membersState = rememberGroupMembersState(client, convId)

    // 点开的成员资料页盖在群资料之上；开着时本页的返回让位给它
    var memberProfile by remember(convId) { mutableStateOf<GroupMember?>(null) }
    // 群成员搜索页（大群专用，GroupMemberSearch.shouldOffer 门控入口）
    var memberSearchOpen by remember(convId) { mutableStateOf(false) }
    // 待审入群申请（G3）。null = 没打开过
    var joinReqs by remember(convId) { mutableStateOf<List<JoinRequest>?>(null) }
    var joinReqsLoading by remember(convId) { mutableStateOf(false) }
    var deciding by remember(convId) { mutableStateOf("") }
    // 群管理二级页（仅群主/管理员能进）
    var managing by remember(convId) { mutableStateOf(false) }
    // 群二维码/群邀请链接页：null=关闭，false=二维码，true=邀请链接（同一份数据，见 GroupQrCardHost）
    var qrCardAsLink by remember(convId) { mutableStateOf<Boolean?>(null) }
    // 会话媒体归档（详情页的「聊天媒体」，与单聊那侧同一个组件）
    // 归档已并进内联页签（2026-09-09），只剩「点开一张图/视频」还是独立的一层
    var pickedTab by remember(convId) { mutableStateOf(initialTab) }
    val archives = rememberConvArchiveSet(client, convId)
    val contacts = com.libeyond.imandroid.ui.screens.rememberContactTab(
        client, convId, isGroup = true,
        remarkOf = { uid -> knownFriends[uid]?.remark },
        groupNameOf = { uid -> membersState.members.firstOrNull { it.userId == uid }?.displayName },
        onOpen = { memberProfile = GroupMember(userId = it.uid, nickname = it.nickname, avatarUrl = it.avatarUrl) },
    )
    // 链接 / 名片要先扫出结果才知道页签出不出现，所以常驻订阅（扫描在后台线程，见 rememberLocalScan）
    val linkMessages = rememberLinkMessages(client, convId, active = true)
    // 只显示有内容的页签（成员恒在，对齐 iOS）；选中项落在集合外时退到第一个可见的
    val tabs = DetailTabs.visible(isGroup = true, nonEmpty = archives.nonEmpty + contentTabs(linkMessages, contacts))
    val tab = if (galleryOnly) pickedTab else DetailTabs.resolve(tabs, pickedTab)
    val archive = archives.of(tab)
    // 媒体查看器：开不开与看哪项拆成两个变量（同 ChatDetailHost 这轮的写法）——关闭只翻
    // viewingOpen，viewingData 留着不清，退场动画那 300ms 里 PushTransition 还要读它。
    var viewingOpen by remember(convId) { mutableStateOf(false) }
    var viewingData by remember(convId) { mutableStateOf<ConvMediaItem?>(null) }
    var archiveMenuFor by remember(convId) { mutableStateOf<ArchiveTarget?>(null) }
    var archiveMenuAnchor by remember(convId) { mutableStateOf(Rect.Zero) }
    var askFriend by remember(convId) { mutableStateOf<FriendRequestTarget?>(null) } // 成员菜单「添加好友」
    val menuSurface = rememberMenuSurface() // 长按时页面模糊 + 那一行原位浮起（同 ChatDetailHost）
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
        memberSearchOpen = memberSearchOpen,
        mediaOpen = viewingOpen,
        managing = managing,
        qrOpen = qrCardAsLink != null,
    )
    BackHandler {
        when (page) {
            GroupInfoPage.Pick -> { pick = null; picked = emptySet() }
            GroupInfoPage.Bans -> bans = null
            GroupInfoPage.Admins -> adminsOpen = false
            GroupInfoPage.JoinRequests -> joinReqs = null
            GroupInfoPage.MemberProfile -> memberProfile = null
            GroupInfoPage.MemberSearch -> memberSearchOpen = false
            GroupInfoPage.Media -> viewingOpen = false
            GroupInfoPage.Manage -> managing = false
            GroupInfoPage.Qr -> qrCardAsLink = null
            GroupInfoPage.Detail -> onBack()
        }
    }

    // 置顶/免打扰/群昵称/群备注（对齐 iOS Settings 区）+ 公告/简介全文，状态见 GroupInfoSettings.kt
    val settings = rememberGroupInfoSettings(client, convId, scope)

    GroupInfoLiveLoad(
        client, convId,
        onInfo = {
            info = it
            infoFailed = false
            val owner = client.uid.orEmpty()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { infoCache.save(owner, it) }
        },
        refreshMembers = { membersState.refresh() },
        loadSettings = { settings.load() },
        reloadKey = infoReload,
        onLoadFailed = { infoFailed = true },
    )

    val g = info ?: run {
        GroupInfoPlaceholder(seed, failed = infoFailed, onRetry = { infoFailed = false; infoReload++ }, onBack = onBack)
        return
    }

    // —— 群管理（G1/G2）——
    var manage by remember(convId) { mutableStateOf<GroupManageAction?>(null) }
    var memberMenu by remember(convId) { mutableStateOf<GroupMember?>(null) }
    var toast by remember(convId) { mutableStateOf<String?>(null) }
    val saveMedia = rememberMediaSaver { toast = it }
    val myUid = client.uid.orEmpty()

    // 管理员身份被撤销：当场收起所有只对群主/管理员开放的页面与弹层（判据与理由见 OnManagerRoleLost）
    OnManagerRoleLost(g.iAmManager, convId) {
        managing = false
        adminsOpen = false
        bans = null
        joinReqs = null
        manage = null
        memberMenu = null
        confirmTransfer = null
        if (pick == PickPurpose.AddAdmin || pick == PickPurpose.Transfer) { pick = null; picked = emptySet() }
        confirmMore = null
        // 主动转让群主成功时 runManage 已先写了「成功」吐司，别拿「你已不是群主」盖掉它
        if (toast == null) toast = Str.s(R.string.group_error_not_admin)
    }

    /**
     * 调完写接口统一刷一次群资料 + 成员首页——服务端是权威，别本地猜新状态。对齐 iOS
     * 每个动作后都调 `loadGroupInfo`（含 `resetSuperMemberPaging`，同样重置到第一页，
     * 代价是丢弃已滚动加载的更深几页，两端同一取舍）。**与 `onLoadMoreMembers` 共享
     * `loading` 标志**：避免深分页时"触底加载更多"与"长按管理动作"并发写
     * `members`/`cursor`/`hasMore`，旧游标数据拼接出成员区间空洞（`/code-review` 抓出）。
     */
    fun runManage(label: String, block: suspend () -> Any?) {
        scope.launch {
            val r = runCatching { block() }
            r.onFailure { e ->
                val code = (e as? com.libeyond.imandroid.sdk.http.ApiException)?.code
                // 端上放行了但服务端拒——把码带出来，别只说「失败」
                toast = if (code != null) Str.s(R.string.group_manage_op_failed_with_code, label, code)
                    else Str.s(R.string.group_manage_op_failed, label)
                IMLog.tag("IM.Group").w("group_manage_failed", "op" to label, "code" to (code ?: -1))
            }
            if (r.isSuccess) toast = (r.getOrNull() as? ManageToast)?.text ?: Str.s(R.string.group_manage_op_succeeded, label)
            runCatching { client.groups.info(convId) }.onSuccess { info = it }
            membersState.refresh()
        }
    }

    // 换群头像：选图 → 压成 JPEG → POST /avatar → PUT /groups/{id}（**整体替换**，
    // 名字与简介必须原样带回，见 PROTOCOL §11）。整条链与"为什么立刻提交"在 GroupAvatarPicker.kt。
    val pickGroupAvatar = rememberGroupAvatarPicker(
        client = client,
        convId = convId,
        info = { info },
        scope = scope,
        onToast = { toast = it },
        runManage = ::runManage,
    )

    /** 拉全量（待处理 + 已处理）——只拉待处理的话，审批完列表会空掉，看着像没生效。 */
    suspend fun reloadJoinRequests() {
        runCatching { client.groups.joinRequests(convId) }
            .onSuccess { joinReqs = it }
            .onFailure { IMLog.tag("IM.Group").w("join_requests_failed") }
    }

    LaunchedEffect(convId) {
        if (openJoinRequests) { joinReqs = emptyList(); joinReqsLoading = true; reloadJoinRequests(); joinReqsLoading = false }
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
        // 三种用途共用一个选择页；接线（点下去之后做什么）在 GroupPickHost（GroupPickPage.kt）
        GroupPickHost(
            client = client, convId = convId, purpose = pk, members = membersState.members, friends = friends,
            picked = picked, myUid = myUid, scope = scope, runManage = ::runManage,
            onPicked = { picked = it }, onToast = { toast = it }, onInfo = { info = it },
            refreshMembers = { membersState.refresh() },
            onClose = { pick = null; picked = emptySet() },
            onRequestTransfer = { id -> confirmTransfer = membersState.members.firstOrNull { it.userId == id } },
        )
    } else if (bans != null) {
        GroupBanListScreen(
            bans = bans.orEmpty(),
            loading = bansLoading,
            busyUid = deciding,
            onUnban = { uid ->
                deciding = uid
                scope.launch {
                    toast = unbanText(client, convId, uid)
                    runCatching { client.groups.bans(convId) }.onSuccess { bans = it }
                    deciding = ""
                }
            },
            onBack = { bans = null },
        )
    } else if (adminsOpen) {
        GroupAdminListHost(
            client = client, convId = convId, info = g, members = membersState.members, myUid = myUid,
            onOpenMember = { memberProfile = it },
            onAdd = { pick = PickPurpose.AddAdmin; picked = emptySet() },
            onToast = { toast = it },
            onInfo = { info = it },
            refreshMembers = { membersState.refresh() },
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
                    toast = reviewJoinRequestText(client, convId, uid, approve)
                    // 无论成败都重拉：失败可能是别人已经审过了，本地那条状态已经不对了
                    reloadJoinRequests()
                    // 顺带刷群资料，pending_count 角标要跟着掉
                    runCatching { client.groups.info(convId) }.onSuccess { info = it }
                client.messages.refreshConversations() // 会话列表的「[N 待审]」红字也要跟着掉
                    deciding = ""
                }
            },
            onBack = { joinReqs = null },
        )
    } else if (mp != null) {
        MemberProfileHost(
            client = client,
            userId = mp.userId,
            knownFriends = knownFriends,
            // 全局昵称，**不是** mp.displayName（那个会优先取群昵称）
            name = mp.nickname,
            avatarUrl = mp.avatarUrl,
            onOpenChat = { chat -> memberProfile = null; onOpenChat(chat) },
            onBack = { memberProfile = null },
        )
    } else if (memberSearchOpen) {
        GroupMemberSearchHost(
            client = client, convId = convId, totalMembers = g.memberCount,
            onPickMember = { m -> memberProfile = m },
            onBack = { memberSearchOpen = false },
        )
    } else if (managing) {
        GroupManageScreen(
            info = g,
            banCount = bans?.size,
            members = membersState.members,
            onManage = { action -> manage = action },
            onToggleSetting = { key ->
                // **整体替换**：五个值一次全传，翻转哪一个由纯函数算（见 GroupSettings）
                val v = GroupSettings.toggled(g, key)
                // 乐观更新：开关立刻翻（否则要等 PUT + 重拉两次往返才变，看着像没生效）；失败由 runManage 的重拉回滚
                info = GroupSettings.applied(g, v)
                runManage(GroupSettings.label(key)) {
                    // PUT 失败要**立刻**回滚本地乐观值：runManage 的重拉也失败（断网）时，开关会一直停在服务端没接受的状态
                    putSettingsOrRollback(client, convId, v) { info = g }
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
            onPickAvatar = if (GroupPermissions.canEditInfo(g)) pickGroupAvatar else null,
            onBack = { managing = false },
        )
    } else if (qrCardAsLink != null) {
        GroupQrCardHost(
            client = client, convId = convId, groupName = g.name, avatarUrl = g.avatarUrl,
            memberCount = g.memberCount, asLink = qrCardAsLink == true, canReset = g.iAmManager,
            onBack = { qrCardAsLink = null },
        )
    } else {
        // Media 与 Detail 是这条链里唯一真的接了转场的一对（同 ChatDetailHost 这轮的写法）：
        // 关闭只翻 viewingOpen，viewingData 留着不清，退场动画期间渲染读的是 PushTransition
        // 冻结下来的 state，不是活变量。其余 8 个分支仍是整页替换，本轮不碰。
        val innerPage = if (viewingOpen) GroupInfoPage.Media else GroupInfoPage.Detail
        PushTransition(targetState = innerPage, depthOf = { if (it == GroupInfoPage.Media) 1 else 0 }) { state ->
        if (state == GroupInfoPage.Media) {
        viewingData?.let { m ->
        // 翻页 / 「更多」/ 转发都在 ArchiveViewer.kt 里，与单聊详情共用
        ArchiveMediaViewer(
            client = client, convId = convId, isGroup = true,
            iAmManager = info?.iAmManager == true,
            archive = archive, current = m, scope = scope,
            // 查看器标题＝群名（iOS `IMMediaPagerViewController.conversationTitle`）
            title = info?.name.orEmpty(),
            onSave = saveMedia,
            onForwardPicker = { archiveForward = it },
            onLocateInChat = { seq -> viewingOpen = false; onLocateInChat(seq) },
            onChanged = { archive.reload() },
            onToast = { toast = it },
            onClose = { viewingOpen = false },
        )
        }
        } else {
        // **整页替换而不是叠一层**：父布局由调用方决定，叠出来可能是竖排；替换还让滚动位置与成员分页游标原样留着
        ProvideMenuSurface(menuSurface) { Box(Modifier.fillMaxSize().menuBackdrop(menuSurface.backdrop)) {
        GroupInfoScreen(
        info = g,
        contacts = contacts,
        upgradeHint = rememberGroupUpgradeHint(client, g, convId) { toast = it },
        members = membersState.members,
        // 语音行发送者名（判据在 data/SenderNames.kt 的 groupVoiceSenderNameOf）
        senderNameOf = groupVoiceSenderNameOf(
            client.uid, membersState.members,
            rememberLocalSenderNames(client, convId, active = tab == DetailTab.Voice),
        ),
        // 波形：服务端归档接口不回带，从本地消息表按 conv_seq 兜底（见 rememberVoiceWaveforms）
        waveformOf = rememberVoiceWaveforms(client, convId, active = tab == DetailTab.Voice)::get,
        hasMoreMembers = membersState.hasMore,
        onLoadMoreMembers = { membersState.loadNext(scope) },
        onOpenMemberSearch = { memberSearchOpen = true },
        onOpenMember = { m -> memberProfile = m },
        myUid = client.uid.orEmpty(),
        onOpenManage = { managing = true },
        pinned = settings.pinned,
        muteValueText = rememberGroupMuteValueText(settings),
        onTogglePinned = settings::togglePinned,
        onOpenMute = settings::openMuteSheet,
        onEditMyNickname = settings::openMyNicknameEditor,
        remark = settings.remark,
        onEditRemark = settings::openRemarkEditor,
        onOpenNotice = settings::openNotice,
        onOpenGroupQR = { qrCardAsLink = false },
        onOpenGroupInviteLink = { qrCardAsLink = true },

            onInvite = {
                pick = PickPurpose.Invite
                picked = emptySet()
                scope.launch {
                    runCatching { client.contacts.friends() }
                        .onSuccess { list -> friends = list.filter { it.status == FriendEntry.ACCEPTED } }
                        .onFailure { toast = Str.s(R.string.net_fallback_friends_load) }
                }
            },
        actions = DetailActions.pillsFor(
            isGroup = true, isSystemPeer = false, peerIsFriend = false, showsMessagePill = false,
            groupCallEnabled = true,
        ),
        moreItems = DetailActions.moreFor(
            isGroup = true, isSystemPeer = false,
            iAmOwner = g.myRole == GroupMember.ROLE_OWNER,
            peerBlocked = false, peerIsFriend = false,
        ),
        onAction = { a ->
            // 群这一侧 pills 是「群通话 / 搜索 / 更多」，更多由 onMore 走；群通话先选成员再拨
            when (a) {
                DetailAction.Search -> onSearchInChat()
                DetailAction.GroupCall -> {
                    picked = emptySet()
                    pick = PickPurpose.Call
                }
                else -> Unit
            }
        },
        onMore = { m -> confirmMore = m },
        onMemberLongPress = { m -> memberMenu = m },
            tabs = tabs,
            tab = tab,
            onTabChange = { pickedTab = it },
            archive = archive.items,
            linkMessages = linkMessages,
            archiveLoading = archive.loading,
            archiveHasMore = archive.hasMore,
            onLoadMoreArchive = { archive.loadMore() },
            onOpenArchive = { item ->
                openArchiveItem(client, context, item, onToast = { toast = it }) { viewingData = it; viewingOpen = true }
            },
            onLongPressArchive = { t, r -> archiveMenuFor = t; archiveMenuAnchor = r },
            onOpenLink = { url -> openLink?.invoke(url) },
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            galleryOnly = galleryOnly,
            onBack = onBack,
        )
        } }
        }
        }
    }
    }

    GroupMuteSheet(settings, g.name) // 「消息免打扰」时长菜单（第二批）

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
                archives.reloadAll() // 归档页签是服务端分页，已加载的要按新位点重载（同 ChatDetailHost）
                toast = Str.s(R.string.chat_detail_clear_history_done)
            }
        },
        onLeave = {
            confirmMore = null
            scope.launch {
                runCatching { client.groups.leave(convId) }
                    .onFailure { toast = it.userMessage(Str.s(R.string.net_fallback_leave_failed)); return@launch }
                client.messages.dropConversation(convId)
                onLeft()
            }
        },
        onDissolve = {
            confirmMore = null
            scope.launch {
                runCatching { client.groups.dissolve(convId) }
                    .onFailure { toast = it.userMessage(Str.s(R.string.net_fallback_dissolve_failed)); return@launch }
                client.messages.dropConversation(convId)
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

    // 设置区弹窗 + 公告/简介全文（拼装在 GroupInfoDialogs.kt）
    GroupInfoSettingsDialogs(
        settings = settings,
        myNickname = g.myNickname,
        groupName = g.name,
        onConfirmMyNickname = { v -> runManage(Str.s(R.string.group_manage_edit_my_nickname)) { client.groups.setMyNickname(convId, v) } },
    )

    // 转让的二次确认（文案在 GroupInfoDialogs.kt，与「更多」那三个确认框同住）
    GroupTransferConfirmDialog(
        member = confirmTransfer,
        onDismiss = { confirmTransfer = null },
        onConfirm = { m ->
            confirmTransfer = null
            pick = null
            runManage(Str.s(R.string.group_manage_transfer_group)) { client.groups.transferOwner(convId, m.userId) }
        },
    )

    ProvideMenuSurface(menuSurface) {
        // —— 成员长按菜单 ——（判据与拼装在 GroupMemberMenu.kt）
        memberMenu?.let { m ->
            GroupMemberMenu(
                member = m, info = g, myUid = myUid, client = client, convId = convId, knownFriends = knownFriends,
                runManage = ::runManage, onOpenChat = onOpenChat, onDismiss = { memberMenu = null },
                onAddFriend = { askFriend = FriendRequestTarget(it.userId, knownFriends[it.userId]?.remark?.ifBlank { null } ?: it.displayName) },
            )
        }
        FriendRequestPrompt(client, askFriend, onDismiss = { askFriend = null }, onToast = { toast = it })
        // 归档长按菜单 + 转发选择页（与单聊详情共用同一份接线）；scope 用宿主的：菜单点完就关，请求不能挂在它身上
        ArchiveActionsHost(
            client = client, convId = convId, isGroup = true, iAmManager = info?.iAmManager == true,
            scope = scope, target = archiveMenuFor, anchor = archiveMenuAnchor,
            onLocateInChat = { seq -> archiveMenuFor = null; onLocateInChat(seq) },
            onChanged = { archive.reload() }, onToast = { toast = it },
            onForwardPicker = { archiveForward = it }, onDismiss = { archiveMenuFor = null },
        )
    }

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
