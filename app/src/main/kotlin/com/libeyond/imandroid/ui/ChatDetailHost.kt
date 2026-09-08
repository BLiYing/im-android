package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.ChatDetailNav
import com.libeyond.imandroid.data.ChatDetailPage
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailActions
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMTextPrompt
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ChatDetailScreen
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import com.libeyond.imandroid.ui.screens.MediaViewerScreen
import com.libeyond.imandroid.ui.screens.linkUrlOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** 「链接」页签扫多少条本地消息。与聊天页的渲染窗口同量级，再多也只是扫更旧的已加载记录。 */
private const val LINK_SCAN_LIMIT = 500

/**
 * 单聊详情接线层（M4.5-3）。
 *
 * **单聊此前只有「用户资料页」**，那回答的是"这个人是谁"；"这段对话怎么设置"
 * （置顶/免打扰/发过哪些媒体）一直没有地方放。iOS 的 `IMChatDetailViewController`
 * 就是这一层，用户资料是它 push 出去的一页。
 */
@Composable
fun ChatDetailHost(
    client: IMClient,
    conv: ConversationEntity,
    /** 本地好友表，进用户资料页时用来定型关系与备注（避免闪动）。 */
    knownFriends: Map<String, FriendEntry>,
    /** 「搜索」pill：关掉本页、回聊天页开搜索态（本页盖在聊天页之上，只能这么绕）。 */
    onSearchInChat: () -> Unit = {},
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = client.uid.orEmpty()

    var profile by remember(conv.convId) { mutableStateOf(false) }
    var viewing by remember(conv.convId) { mutableStateOf<ConvMediaItem?>(null) }
    var toast by remember(conv.convId) { mutableStateOf<String?>(null) }
    val saveMedia = rememberMediaSaver { toast = it }

    // 好友关系是**异步校正**的：外层传进来那份可能是几分钟前的，而操作排的显隐全靠它
    // （非好友只显「加好友」）。进页重拉一次，别拿旧值摆一排必然 4xx 的按钮。
    var friend by remember(conv.convId) { mutableStateOf(knownFriends[conv.peerUid]) }
    LaunchedEffect(conv.convId, conv.peerUid) {
        if (conv.peerUid.isNotEmpty()) {
            runCatching { client.contacts.friends() }
                .onSuccess { list -> friend = list.firstOrNull { it.userId == conv.peerUid } }
        }
    }
    // 「更多」里那几件要二次确认 / 要填一句话的事
    var confirm by remember(conv.convId) { mutableStateOf<DetailMoreAction?>(null) }
    var reporting by remember(conv.convId) { mutableStateOf(false) }
    var sharing by remember(conv.convId) { mutableStateOf(false) }

    var pinned by remember(conv.convId) { mutableStateOf(conv.pinnedAt > 0) }
    var muted by remember(conv.convId) { mutableStateOf(conv.muted) }

    var tab by remember(conv.convId) { mutableStateOf(DetailTab.Media) }
    var archive by remember(conv.convId) { mutableStateOf<List<ConvMediaItem>>(emptyList()) }
    var cursor by remember(conv.convId) { mutableStateOf(0L) }
    var hasMore by remember(conv.convId) { mutableStateOf(false) }
    var loading by remember(conv.convId) { mutableStateOf(false) }

    // 「链接」页签只能扫本地：服务端没有可索引的链接列（media.go 开头写明）。iOS 同样是本地扫。
    val localMessages by remember(conv.convId, owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeMessages(owner, conv.convId, LINK_SCAN_LIMIT)
    }.collectAsState(initial = emptyList<MessageEntity>())
    val linkMessages = remember(localMessages) {
        localMessages.mapNotNull { m ->
            linkUrlOf(m.contentType, m.content, m.convSeq)?.let { m to it }
        }.sortedByDescending { it.first.convSeq }
    }

    val page = ChatDetailNav.current(mediaOpen = viewing != null, profileOpen = profile)
    // 返回键一处派发（同 GroupInfoHost；理由见 ChatDetailPage 的注释）
    BackHandler {
        when (page) {
            ChatDetailPage.Media -> viewing = null
            ChatDetailPage.Profile -> profile = false
            ChatDetailPage.Detail -> onBack()
        }
    }

    /** 换页签 = 从头拉；续页 = 带游标追加。**两条路共用一个出口**，免得分页语义分叉。 */
    fun load(reset: Boolean) {
        val kind = DetailTabs.apiKind(tab) ?: return   // 链接/成员不走这个接口
        if (loading) return                            // 在途守卫：滚到底会连续触发
        loading = true
        scope.launch {
            runCatching { client.conversationsApi.media(conv.convId, kind, if (reset) 0L else cursor) }
                .onSuccess { p ->
                    archive = if (reset) {
                        p.items
                    } else {
                        val seen = archive.mapTo(HashSet()) { it.convSeq }
                        archive + p.items.filter { it.convSeq !in seen }
                    }
                    cursor = p.nextCursor
                    hasMore = p.hasMore
                }
                .onFailure { IMLog.tag("IM.Detail").w("conv_media_failed", "kind" to kind) }
            loading = false
        }
    }

    LaunchedEffect(conv.convId, tab) {
        archive = emptyList()
        cursor = 0
        hasMore = false
        load(reset = true)
    }

    /** 会话设置是**整体替换**三项：改一项也要把另外两项原样带回，否则会顺手清掉。 */
    fun pushSettings(newPinned: Boolean, newMuted: Boolean) {
        scope.launch {
            runCatching {
                client.conversationsApi.updateSettings(
                    conv.convId,
                    pinnedAt = if (newPinned) System.currentTimeMillis() else 0,
                    muted = newMuted,
                    markedUnread = conv.markedUnread,
                )
            }.onFailure { IMLog.tag("IM.Detail").w("conv_settings_failed") }
            client.messages.refreshConversations()
        }
    }

    when (page) {
        ChatDetailPage.Media -> viewing?.let { m ->
            MediaViewerScreen(
                contentType = m.contentType,
                content = m.content,
                poster = m.poster,
                localFile = client.downloads.localFile(m.content, m.contentType == ContentType.VIDEO),
                host = client.host,
                useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
                onSave = saveMedia,
                // 归档里**没有转发**：转发选择页与发送上下文都在聊天页那一侧。
                // 按钮不画，不做成点了没反应的。
                onForward = null,
                onClose = { viewing = null },
            )
        }
        ChatDetailPage.Profile -> {
            val f = knownFriends[conv.peerUid]
            UserProfileHost(
                client = client,
                userId = conv.peerUid,
                knownRelation = f?.status.orEmpty(),
                seed = UserCard(
                    userId = conv.peerUid,
                    nickname = conv.title,
                    avatarUrl = conv.avatarUrl,
                    remark = conv.peerRemark,
                ),
                onSendMessage = { profile = false },
                onBack = { profile = false },
            )
        }
        ChatDetailPage.Detail -> ChatDetailScreen(
            conv = conv,
            title = conv.title.ifBlank { conv.peerUid },
            handle = knownFriends[conv.peerUid]?.handle.orEmpty(),
            remark = conv.peerRemark,
            pinned = pinned,
            muted = muted,
            tab = tab,
            onTabChange = { tab = it },
            archive = archive,
            linkMessages = linkMessages,
            loading = loading,
            hasMore = hasMore,
            onLoadMore = { if (hasMore) load(reset = false) },
            onOpenArchive = { item ->
                // **文件不进图片查看器**：那里没有文件分支，一个 PDF 会被当成图片
                // 交给 ZoomableImage，屏幕上一片空白（2026-09-08 查出来的死路）。
                if (item.contentType == ContentType.FILE) {
                    client.downloads.localFile(item.content)?.let { f ->
                        OpenFile.open(context, f, MediaUrl.displayFileName(item.content, item.fileName))
                            ?.let { toast = it }
                    } ?: run { toast = "文件不在本地，请先下载" }
                } else {
                    viewing = item
                }
            },
            // 链接用系统浏览器打开。**不做"定位到聊天"**：本端还没有跳到指定 conv_seq 的能力，
            // 与其做个跳回去但落在别处的假跳转，不如先给一个真的有用的动作。
            onOpenLink = { url ->
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { toast = "没有能打开这个链接的应用" }
            },
            onTogglePinned = { v -> pinned = v; pushSettings(v, muted) },
            onToggleMuted = { v -> muted = v; pushSettings(pinned, v) },
            // 备注名改在用户资料页里（那里已有输入框与 setRemark 接线），不在这里重复一套
            onSetRemark = { profile = true },
            onOpenProfile = { profile = true },
            actions = DetailActions.pillsFor(
                isGroup = false,
                isSystemPeer = DetailActions.isSystemPeer(conv.peerUid),
                peerIsFriend = friend?.status == FriendEntry.ACCEPTED,
                // 本页是从**聊天页**点头像进来的，会话已经开着——再给一个「消息」是废按钮。
                // 从通讯录/群成员进来的那条路走的是 UserProfileHost，不经这里。
                showsMessagePill = false,
            ),
            moreItems = DetailActions.moreFor(
                isGroup = false,
                isSystemPeer = DetailActions.isSystemPeer(conv.peerUid),
                iAmOwner = false,
                peerBlocked = friend?.blocked == true,
                peerIsFriend = friend?.status == FriendEntry.ACCEPTED,
            ),
            onAction = { a ->
                when (a) {
                    // 与 iOS 同：pill 点了回聊天页进搜索态（SEARCH_DESIGN §4）
                    DetailAction.Search -> onSearchInChat()
                    DetailAction.Call -> toast = "语音通话即将上线"
                    DetailAction.Video -> toast = "视频通话即将上线"
                    DetailAction.AddFriend -> scope.launch {
                        runCatching { client.contacts.request(conv.peerUid) }
                            .onSuccess { toast = "好友申请已发出" }
                            .onFailure { toast = it.userMessage("加好友失败") }
                    }
                    // 已经在这个会话里了，这两个不会出现在 pills 里
                    DetailAction.Message, DetailAction.More -> Unit
                }
            },
            onMore = { m ->
                when (m) {
                    DetailMoreAction.ShareContact -> sharing = true
                    DetailMoreAction.Report -> reporting = true
                    DetailMoreAction.Unblock -> scope.launch {
                        runCatching { client.contacts.unblock(conv.peerUid) }
                            .onSuccess { toast = "已取消拉黑"; friend = friend?.copy(blocked = false) }
                            .onFailure { toast = it.userMessage("操作失败") }
                    }
                    // 其余都要二次确认（拉黑/清空/删好友都不可撤销或代价大）
                    else -> confirm = m
                }
            },
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            onBack = onBack,
        )
    }

    // —— 「更多」的二次确认 / 填理由 / 选会话 ——
    val peerName = conv.peerRemark.ifBlank { conv.title }
    when (confirm) {
        DetailMoreAction.Block -> IMConfirmDialog(
            title = "拉黑「$peerName」？",
            message = "拉黑后不再收到对方消息。可随时取消。",
            confirmText = "拉黑",
            onConfirm = {
                confirm = null
                scope.launch {
                    runCatching { client.contacts.block(conv.peerUid) }
                        .onSuccess { toast = "已拉黑"; friend = friend?.copy(blocked = true) }
                        .onFailure { toast = it.userMessage("拉黑失败") }
                }
            },
            onDismiss = { confirm = null },
        )
        DetailMoreAction.ClearHistory -> IMConfirmDialog(
            title = "清空聊天记录？",
            message = "将删除此会话在本机的全部消息，且无法恢复。",
            confirmText = "清空",
            onConfirm = {
                confirm = null
                scope.launch {
                    // **只删本机**（同 iOS `clearMessagesForConv:`）：服务端没有、也不该有
                    // 「替所有人删历史」的接口
                    client.repo.clearConversation(owner, conv.convId)
                    toast = "聊天记录已清空"
                }
            },
            onDismiss = { confirm = null },
        )
        DetailMoreAction.RemoveFriend -> IMConfirmDialog(
            title = "删除好友「$peerName」？",
            message = "将从通讯录移除，聊天记录仍保留在本机。",
            confirmText = "删除",
            onConfirm = {
                confirm = null
                scope.launch {
                    runCatching { client.contacts.remove(conv.peerUid) }
                        .onSuccess {
                            toast = "已删除好友"
                            // **不退页**：重拉关系后本页自然切成非好友视图，用户当场看得到关系已变
                            runCatching { client.contacts.friends() }
                                .onSuccess { l -> friend = l.firstOrNull { it.userId == conv.peerUid } }
                        }
                        .onFailure { toast = it.userMessage("删除失败") }
                }
            },
            onDismiss = { confirm = null },
        )
        else -> Unit
    }

    if (reporting) {
        IMTextPrompt(
            title = "举报「$peerName」", initial = "", maxLen = 200, multiline = true,
            onDismiss = { reporting = false },
            onConfirm = { reason ->
                reporting = false
                scope.launch {
                    // target_type=user：举报**这个人**。聊天页长按那个是 target_type=message，
                    // 两个入口互补，别合并（iOS 合并消息侧两项时差点丢掉人侧那个）。
                    runCatching { client.contacts.report("user", conv.peerUid, conv.convId, reason) }
                        .onSuccess { toast = "举报已提交，感谢反馈。" }
                        .onFailure { toast = it.userMessage("举报失败") }
                }
            },
        )
    }

    if (sharing) {
        val convs by client.repo.observeConversations(owner).collectAsState(initial = emptyList())
        ForwardPickerScreen(
            conversations = convs.filter { it.convId != conv.convId },
            count = 1,
            onCancel = { sharing = false },
            onToast = { toast = it },
            onConfirm = { targets ->
                sharing = false
                scope.launch {
                    // **写进名片的是公开名**（不是备注）——这段 JSON 会原样发给第三个人。
                    // iOS 与 im-web 各为此出过一次事故（IMServer docs/UI.md 隐私红线）。
                    val json = CardContent.encodeContact(
                        uid = conv.peerUid,
                        username = friend?.username.orEmpty(),
                        nickname = friend?.let { DisplayName.publicNameOfFriend(it) }.orEmpty(),
                        avatarUrl = conv.avatarUrl,
                    )
                    for (t in targets) {
                        runCatching {
                            client.messages.sendCard(
                                convId = t.convId,
                                to = if (t.isGroup) "" else t.peerUid,
                                contentType = com.libeyond.imandroid.sdk.protocol.ContentType.CONTACT,
                                json = json,
                            )
                        }
                    }
                    toast = if (targets.size > 1) "已发送到 ${targets.size} 个会话" else "已发送"
                }
            },
        )
    }

    // toast 放最后：它是一层 fillMaxSize 的浮层，画在页面之前会被页面盖住
    toast?.let { t -> IMToast(t) { toast = null } }
}
