package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ChatDetailNav
import com.libeyond.imandroid.data.ChatDetailPage
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.ChatDetailScreen
import kotlinx.coroutines.launch

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

    var profile by remember(conv.convId) { mutableStateOf(false) }
    var mediaOpen by remember(conv.convId) { mutableStateOf(false) }

    // 置顶/免打扰：本地先认会话行的值，改完由 refreshConversations 带回权威值
    var pinned by remember(conv.convId) { mutableStateOf(conv.pinnedAt > 0) }
    var muted by remember(conv.convId) { mutableStateOf(conv.muted) }

    // **每页各自记住自己的滚动位置**。本页用"整页替换"做导航，切页时旧页整个离开组合，
    // 没有 SaveableStateHolder 的话 rememberScrollState / LazyListState 全部丢失——
    // 表现是：从 2000 人成员列表点进一个人，返回后弹回列表顶部。
    // iOS 的 push/pop 天然保住这些，本端得自己兜。
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

    val page = ChatDetailNav.current(mediaOpen = mediaOpen, profileOpen = profile)
    // 返回键一处派发（同 GroupInfoHost；理由见 ChatDetailPage 的注释）
    BackHandler {
        when (page) {
            ChatDetailPage.Media -> mediaOpen = false
            ChatDetailPage.Profile -> profile = false
            ChatDetailPage.Detail -> onBack()
        }
    }

    /**
     * 会话设置是**整体替换**三项（`PUT /conversations/{id}/settings`）：
     * 改一项也要把另外两项原样带回，否则会顺手把它们清掉——与群治理开关组同一个坑。
     */
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

    stateHolder.SaveableStateProvider(page) {
    when (page) {
        // **与群聊详情共用 ConvMediaHost**：归档在两种会话里完全一样，
        // 分两份的代价不是重复代码，是分页语义会分叉
        ChatDetailPage.Media -> ConvMediaHost(
            client = client,
            convId = conv.convId,
            onBack = { mediaOpen = false },
        )
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
            pinned = pinned,
            muted = muted,
            onTogglePinned = { v -> pinned = v; pushSettings(v, muted) },
            onToggleMuted = { v -> muted = v; pushSettings(pinned, v) },
            onOpenProfile = { profile = true },
            onOpenMedia = { mediaOpen = true },
            onBack = onBack,
        )
    }
    }
}
