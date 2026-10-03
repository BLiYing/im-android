package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.UserProfileScreen
import kotlinx.coroutines.launch

/**
 * 单聊资料页接线层。
 *
 * **进页即按本地已知关系定型**：先用本地好友表里的 status 渲染，再去拉名片补细节。
 * 反过来（先渲染成陌生人、等接口回来再变好友）会让用户看到界面闪一下——
 * 2026-08-30 三端为此专门收口过。
 */
@Composable
fun UserProfileHost(
    client: IMClient,
    userId: String,
    /** 本地已知关系，进页即用它定型。 */
    knownRelation: String,
    /** 本地已知的名片（来自会话/好友列表），先拿它渲染。 */
    seed: UserCard,
    onSendMessage: (UserCard) -> Unit,
    /**
     * 备注改成功后回调新值（`/code-review` 抓出：不接的话，从 `ChatDetailHost` 下钻到
     * 本页改备注，退回去详情页顶部标题/语音发送者名/拉黑确认框标题仍显编辑前的旧值，
     * 因为两处各自持有一份 `remark` 状态、互不同步；反方向——详情页内弹窗改——是同步的，
     * 因为下钻时 `seed.remark` 取的就是详情页当时的最新值，容易让人误以为已经双向同步）。
     */
    onRemarkChanged: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    // **点到自己 → 个人资料页（可编辑），不是「用户信息页」**（对齐 iOS：聊天头像 / 群成员 / 聊天记录里点自己，
    // 都进 `IMProfileEditViewController`）。六处入口（通讯录 / 群资料 / 收藏 / 扫码 / 详情页）都经本函数，
    // 在这里一处收口，别在各调用点各判一遍——漏一处就是一处「点自己看到加好友」。
    if (userId.isNotEmpty() && userId == client.uid) {
        SelfProfile(client, onBack)
        return
    }
    val scope = rememberCoroutineScope()
    var card by remember(userId) { mutableStateOf(seed) }
    var relation by remember(userId) { mutableStateOf(knownRelation) }
    var editingRemark by remember(userId) { mutableStateOf(false) }
    var askFriend by remember(userId) { mutableStateOf<FriendRequestTarget?>(null) }
    var toast by remember(userId) { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(userId) {
        runCatching { card = client.contacts.card(userId) }
            .onFailure { IMLog.tag("IM.Profile").w("card_fetch_failed") }
        // 关系以本地好友表为准（服务端名片不带 status）
        runCatching {
            client.contacts.friends().firstOrNull { it.userId == userId }?.let { relation = it.status }
        }
    }

    Box(Modifier.fillMaxSize()) {
        UserProfileScreen(
            card = card,
            relation = relation,
            onSendMessage = { onSendMessage(card) },
            onAddFriend = {
                if (relation == FriendEntry.PENDING) {
                    // 对方先申请过我：直接同意，不必再填验证消息
                    scope.launch {
                        runCatching { client.contacts.accept(userId) }
                        relation = FriendEntry.ACCEPTED
                    }
                } else {
                    askFriend = FriendRequestTarget(userId, card.remark.ifBlank { card.nickname })
                }
            },
            onSetRemark = { editingRemark = true },
            onRemoveFriend = {
                scope.launch {
                    runCatching { client.contacts.remove(userId) }
                    relation = ""
                }
            },
            onBack = onBack,
        )
    }

    if (editingRemark) {
        RemarkEditDialog(
            current = card.remark,
            placeholderNickname = card.nickname,
            onDismiss = { editingRemark = false },
            onConfirm = { v ->
                editingRemark = false
                scope.launch {
                    runCatching { client.contacts.setRemark(userId, v) }
                    // 备注只在本机渲染生效，刷一次会话列表让标题跟着变
                    client.messages.refreshConversations()
                    card = card.copy(remark = v)
                    onRemarkChanged(v)
                }
            },
        )
        FriendRequestPrompt(client, askFriend, onDismiss = { askFriend = null }, onToast = { toast = it }) { became ->
            relation = if (became) FriendEntry.ACCEPTED else FriendEntry.REQUESTED
        }
        toast?.let { t -> com.libeyond.imandroid.ui.components.IMToast(t) { toast = null } }
    }
}

/** 自己的个人资料页：先用本机副本顶上（断网也有真名字），进页再重拉一次；保存后回写副本。 */
@Composable
private fun SelfProfile(client: IMClient, onBack: () -> Unit) {
    var me by remember { mutableStateOf<UserCard?>(client.cachedMyProfile()) }
    LaunchedEffect(Unit) {
        runCatchingCancellable { client.contacts.me() }
            .onSuccess { me = it; client.cacheMyProfile(it) }
    }
    MyProfileHost(
        client = client,
        card = me,
        onChanged = { me = it; client.cacheMyProfile(it) },
        onBack = onBack,
    )
}
