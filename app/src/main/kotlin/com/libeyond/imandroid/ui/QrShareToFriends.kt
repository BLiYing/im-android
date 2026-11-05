package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.sendText
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 二维码页「分享」先选发送方式（SHARE_PICKER_UX_SKETCH ⑤）：「发送给朋友」走应用内分享选择页，
 * 「其他应用…」是原来的系统分享面板（行为不变）。
 */
@Composable
internal fun QrShareMethodSheet(
    title: String,
    onToFriends: () -> Unit,
    onOtherApps: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    ActionSheet(
        title = title,
        items = listOf(
            SheetItem(stringResource(R.string.share_method_to_friends), onClick = onToFriends),
            SheetItem(stringResource(R.string.share_method_other_apps), onClick = onOtherApps),
        ),
        onDismiss = onDismiss,
    )
}

/** 群邀请要发出去的文字：「邀请你加入群聊「X」」+ 换行 + `/q/g/<token>` 链接（对方点开进入群预览页）。 */
internal fun groupInviteText(groupName: String, link: String): String =
    Str.s(R.string.share_group_invite_text, groupName) + "\n" + link

/**
 * 群码 / 群邀请链接「发送给朋友」：开分享选择页（可选好友、群、最近聊天），对每个目标发一条文本消息。
 *
 * [scope] 由宿主（群码页）传：选择页先关掉自己再回调，挂它自己身上的协程会当场被取消。
 */
@Composable
internal fun GroupInviteSendPicker(
    client: IMClient,
    /** 被邀请的群自己：不出现在可选目标里（往群里发它自己的邀请链接没有意义）。 */
    convId: String,
    groupName: String,
    link: String,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
) {
    val owner = client.uid.orEmpty()
    val convs by remember(owner) { client.repo.observeConversations(owner) }.collectAsState(initial = emptyList())
    val text = remember(groupName, link) { groupInviteText(groupName, link) }
    ForwardPickerScreen(
        conversations = convs,
        onCancel = onClose,
        onToast = onToast,
        sources = rememberSharePickSources(client),
        preview = text,
        exclude = remember(convId) { setOf(convId) },
        onConfirm = { targets ->
            onClose()
            scope.launch {
                val sent = targets.count { t ->
                    runCatchingCancellable { client.messages.sendText(t.convId, if (t.isGroup) "" else t.peerUid, text) }.isSuccess
                }
                onToast(
                    when {
                        sent == 0 -> Str.s(R.string.common_send_failed)
                        sent == 1 -> Str.s(R.string.common_sent)
                        else -> Str.p(R.plurals.common_sent_to_chats, sent, sent)
                    },
                )
            }
        },
    )
}
