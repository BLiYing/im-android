package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard

/**
 * 「点一个人 → 他的资料页」的**唯一入口**（对齐 iOS：聊天头像 / 群成员 / 通讯录 / 扫码 / 收藏 / 名片 / 聊天记录
 * 全部 push 同一个 `IMChatDetailViewController` + `showsMessagePill = YES`）。
 *
 * 此前聊天头像走 [ChatDetailHost]（呼叫 / 视频 / 归档齐全），群资料 / 通讯录 / 收藏 / 扫码却各自走一页简版
 * `UserProfileHost`，同一个人点进去看到两套页面。收口在这里，别在各调用点各拼一遍。
 *
 * - **点到自己** → 「我的资料」（可编辑）；仍由 [UserProfileHost] 的自我分支收口，这里只转交。
 * - **其他人** → 以他为对端的单聊信息页；[onOpenChat] 由「消息」pill 触发（这个单聊可能压根没打开过）。
 */
@Composable
fun MemberProfileHost(
    client: IMClient,
    userId: String,
    /** 本地好友表：信息页进页即用它定型关系与备注，避免闪动。 */
    knownFriends: Map<String, FriendEntry>,
    /** 本地已知的名字 / 头像（群成员行、名片等）；好友表里有就以好友为准。拿不到就空，信息页自己联网补。 */
    name: String = "",
    avatarUrl: String = "",
    onOpenChat: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
) {
    if (userId.isNotEmpty() && userId == client.uid) {
        UserProfileHost(
            client = client, userId = userId, knownRelation = "", seed = UserCard(userId = userId),
            onSendMessage = {}, onBack = onBack,
        )
        return
    }
    val f = knownFriends[userId]
    val shownName = f?.nickname?.ifBlank { null } ?: name
    val shownAvatar = f?.avatarUrl?.ifBlank { null } ?: avatarUrl
    val stubConv = remember(userId, shownName, shownAvatar) { client.conversationStubFor(userId, shownName, shownAvatar) }
    ChatDetailHost(
        client = client,
        conv = stubConv,
        knownFriends = knownFriends,
        showsMessagePill = true,
        onOpenChat = onOpenChat,
        onBack = onBack,
    )
}
