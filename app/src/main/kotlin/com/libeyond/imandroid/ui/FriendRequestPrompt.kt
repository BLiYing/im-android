package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMTextPrompt
import kotlinx.coroutines.launch

/** 要加的人：uid + 本机显示名（弹窗里写「发送给 xx」）。 */
data class FriendRequestTarget(val userId: String, val name: String)

/**
 * 加好友的验证消息弹窗（对齐 iOS `im_askFriendRequestForUID:name:onSent:`）——**所有「发起申请」入口共用**：
 * 资料页 / 添加好友页 / 会话详情 pill / 聊天里被拒收（200103）的「发送好友申请」。
 * 此前四处都直接 `contacts.request(uid)`、恒不带 hello（矩阵 176）。
 *
 * 预填「我是<我的公开昵称>」（微信同款）——这句会发出去，所以只取**公开昵称**，绝不取备注/本机显示名。
 * 上限 50（服务端也截，端上先卡住免得用户以为全发出去了）。
 *
 * @param onSent 发成功后回调，参数 = 是否已直接成为好友（此时不得提示「已发送」，由本函数统一弹对应提示）。
 */
@Composable
fun FriendRequestPrompt(
    client: IMClient,
    target: FriendRequestTarget?,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
    onSent: (becameFriend: Boolean) -> Unit = {},
) {
    // scope 必须在早退**之前**取：确认时会先把 target 置空（弹窗关闭），早退之后 remember 被丢弃，
    // 正在发的请求连同「已发送」提示与 onSent 一起被取消（/code-review：请求发出去了但界面毫无反应）
    val scope = rememberCoroutineScope()
    if (target == null) return
    // 预填要用我的公开昵称，而本机副本只有进过「我」页才有：没有就先拉一次（拉不到按空预填），再弹窗——
    // IMTextPrompt 的初值只在首次组合时读，不能先弹后补。
    val myNick by produceState<String?>(null, target) {
        value = client.cachedMyProfile()?.nickname
            ?: runCatchingCancellable { client.contacts.me().also { client.cacheMyProfile(it) } }.getOrNull()?.nickname.orEmpty()
    }
    val nick = myNick ?: return
    val shown = target.name.ifBlank { stringResource(R.string.friend_request_peer_fallback) }
    IMTextPrompt(
        title = stringResource(R.string.common_add_friend),
        initial = if (nick.isNotBlank()) Str.s(R.string.friend_request_hello_prefill, nick) else "",
        label = stringResource(R.string.friend_request_placeholder),
        maxLen = HELLO_MAX,
        hint = stringResource(R.string.friend_request_alert_message, shown),
        confirmText = stringResource(R.string.common_send),
        onConfirm = { hello ->
            onDismiss()
            scope.launch {
                runCatchingCancellable { client.contacts.request(target.userId, hello.trim()) }
                    .onSuccess { became ->
                        onToast(Str.s(if (became) R.string.friend_request_became_friends else R.string.friend_request_sent))
                        onSent(became)
                    }
                    .onFailure { onToast(it.userMessage(Str.s(R.string.friend_request_send_failed))) }
            }
        },
        onDismiss = onDismiss,
    )
}

private const val HELLO_MAX = 50
