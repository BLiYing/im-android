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
import com.libeyond.imandroid.data.ChatDetailNav
import com.libeyond.imandroid.data.ChatDetailPage
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ChatDetailScreen
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
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = client.uid.orEmpty()

    var profile by remember(conv.convId) { mutableStateOf(false) }
    var viewing by remember(conv.convId) { mutableStateOf<ConvMediaItem?>(null) }
    var toast by remember(conv.convId) { mutableStateOf<String?>(null) }
    val saveMedia = rememberMediaSaver { toast = it }

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
            onOpenArchive = { viewing = it },
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
            onSearchHistory = { toast = "查找聊天记录还没做" },
            onClearHistory = { toast = "清空聊天记录还没做" },
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            onBack = onBack,
        )
    }

    // toast 放最后：它是一层 fillMaxSize 的浮层，画在页面之前会被页面盖住
    toast?.let { t -> IMToast(t) { toast = null } }
}
