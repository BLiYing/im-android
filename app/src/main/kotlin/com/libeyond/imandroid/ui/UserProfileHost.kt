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
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var card by remember(userId) { mutableStateOf(seed) }
    var relation by remember(userId) { mutableStateOf(knownRelation) }
    var editingRemark by remember(userId) { mutableStateOf(false) }

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
                scope.launch {
                    runCatching {
                        if (relation == FriendEntry.PENDING) client.contacts.accept(userId)
                        else client.contacts.request(userId)
                    }
                    relation = if (relation == FriendEntry.PENDING) FriendEntry.ACCEPTED else FriendEntry.REQUESTED
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
                }
            },
        )
    }
}
