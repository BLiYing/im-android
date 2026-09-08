package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.ui.components.IMConfirmDialog

/**
 * 群详情页头部「更多」的三个二次确认框（清空本机记录 / 退群 / 解散）。
 *
 * 从 `GroupInfoHost` 抽出来只有一个理由：**它是纯 UI**，标题与正文逐字对齐 iOS
 * `confirmClearHistory` / `confirmLeaveGroup` / `confirmDissolve`，而 Host 那边
 * 只该关心"确认之后调哪个接口"。文案要改的话改这里，三端的措辞在
 * `IMChatDetailViewController+Actions.m` 里有原本。
 */
@Composable
internal fun GroupMoreConfirmDialog(
    action: DetailMoreAction?,
    groupName: String,
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit,
    onLeave: () -> Unit,
    onDissolve: () -> Unit,
) {
    when (action) {
        DetailMoreAction.ClearHistory -> IMConfirmDialog(
            title = "清空聊天记录？",
            // **「仅清空本机」这半句不能省**：群聊里少了它，用户会以为自己替全群删了历史
            message = "仅清空本机记录，不影响其他成员。",
            confirmText = "清空",
            onConfirm = onClearHistory,
            onDismiss = onDismiss,
        )
        DetailMoreAction.LeaveGroup -> IMConfirmDialog(
            title = "退出「$groupName」？",
            message = "退出后将不再接收此群消息。",
            confirmText = "退出",
            onConfirm = onLeave,
            onDismiss = onDismiss,
        )
        DetailMoreAction.DissolveGroup -> IMConfirmDialog(
            title = "删除并解散「$groupName」？",
            message = "所有成员将被移出，聊天记录无法恢复，此操作不可撤销。",
            confirmText = "删除",
            onConfirm = onDissolve,
            onDismiss = onDismiss,
        )
        else -> Unit
    }
}
