package com.libeyond.imandroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.blockPointerInput
import com.libeyond.imandroid.ui.screens.FriendPickerScreen
import com.libeyond.mediapicker.MediaPickerHost
import com.libeyond.mediapicker.PickedMedia

/**
 * 聊天页的四层「覆盖在聊天页之上的整页」：用户资料 / 选联系人发名片 / 相册选择页 / 从收藏发送。
 *
 * 从 `ChatHost` 拆出（2026-09-16，那个文件到 587/600 行；套路同 `ChatViewerLayer` 与
 * `ChatRecordLayer`）。这三层的共同点是**整页盖住聊天页、各自只有一个开关状态、关掉就没了**，
 * 所以是一条干净的切口——它们之间没有共享状态，也不参与滚动时序那一摊。
 *
 * **渲染顺序即层级**，别重排：资料页要能盖在名片选择页之上（从名片页点进一个人）。
 * 返回键不在这里管——分层判据在 `ChatOverlays.Layer`，由宿主一处派发（有测试钉着）。
 */
@Composable
internal fun ChatPickerLayers(
    client: IMClient,
    /** 点 @提及 / 头像 / 系统消息里的名字进的成员资料页（uid；null = 没在看）。 */
    openUser: String?,
    /** 本地好友表：资料页进页即用它定型关系与备注，避免闪动。 */
    friendsByUid: Map<String, FriendEntry>,
    /** 本群成员表 uid→显示名/头像：资料页首帧就有名字，不必等联网结果（超级群拿不到就空表）。 */
    memberNames: Map<String, String> = emptyMap(),
    memberAvatars: Map<String, String> = emptyMap(),
    /** 资料页里点「发消息」：关掉本层、换成与该成员的单聊（由 [ChatHost] 转交 `MainScreen`）。 */
    onOpenChat: (ConversationEntity) -> Unit = {},
    /** 选联系人发名片中（null = 不在选）。 */
    pickingFriend: List<FriendEntry>?,
    /** 选图中（自建相册页；无权限时它自己会降级到系统选择器）。 */
    picking: Boolean,
    onCloseUser: () -> Unit,
    onCancelFriendPicker: () -> Unit,
    onPickFriend: (FriendEntry) -> Unit,
    onPicked: (List<PickedMedia>, Boolean) -> Unit,
    onDismissPicker: () -> Unit,
    /** 「从收藏发送」选择页开着。 */
    pickingFavorites: Boolean,
    onCancelFavorites: () -> Unit,
    /** 选好了：交回已换成消息的收藏（按收藏列表顺序），由聊天页发进本会话。 */
    onFavoritesPicked: (List<MessageEntity>) -> Unit,
    onToast: (String) -> Unit,
) {
    // —— 点 @提及 / 头像 / 系统消息里的名字 → 群成员资料页 ——
    // 与单聊自己的「聊天信息」同一个页面（呼叫/视频齐全），只是多一条「发消息」pill
    // （`showsMessagePill`）——不是简化版的加好友资料卡，对齐 iOS `openMemberProfileForUID:`
    // 的 `IMChatDetailViewController` + `showsMessagePill = YES`。
    openUser?.let { uid ->
        MemberProfileHost(
            client = client,
            userId = uid,
            knownFriends = friendsByUid,
            name = memberNames[uid].orEmpty(),
            avatarUrl = memberAvatars[uid].orEmpty(),
            onOpenChat = { chat -> onCloseUser(); onOpenChat(chat) },
            onBack = onCloseUser,
        )
    }

    // —— 选联系人发名片（覆盖在聊天页之上）——
    pickingFriend?.let { list ->
        FriendPickerScreen(
            friends = list,
            onCancel = onCancelFriendPicker,
            onPick = onPickFriend,
        )
    }

    // —— 相册选择页（覆盖在聊天页之上）——
    if (picking) {
        MediaPickerHost(
            skin = rememberPickerSkin(),
            onPicked = onPicked,
            onDismiss = onDismissPicker,
            onToast = onToast,
            log = PickerLog,
        )
    }

    // —— 从收藏发送：收藏页本身的选择模式（iOS 模态呈现同一个 `IMFavoritesViewController`）——
    // 占住命中测试：收藏页的空白处不能把点击漏给下面的聊天页
    if (pickingFavorites) {
        Box(Modifier.fillMaxSize().blockPointerInput()) {
            FavoritesHost(
                client = client,
                // 名片进的资料页点「发消息」：本页没法换会话，先回到聊天页（同上面资料页的 onSendMessage）
                onOpenChat = { onCancelFavorites() },
                onBack = onCancelFavorites,
                onPicked = onFavoritesPicked,
            )
        }
    }
}
