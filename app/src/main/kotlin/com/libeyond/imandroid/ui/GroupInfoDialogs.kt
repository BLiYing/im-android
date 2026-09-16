package com.libeyond.imandroid.ui
import com.libeyond.imandroid.ui.screens.GroupManageAction
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMTextPrompt

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

/**
 * 转让群主的二次确认。
 *
 * 与「设管理员」刻意不对称：设管理员可撤销，选中即执行；**转让不可逆**，
 * 且转让后自己当场变普通成员——这两句必须都说出来（同 iOS `confirmTransferOwner:`）。
 */
@Composable
internal fun GroupTransferConfirmDialog(
    member: GroupMember?,
    onDismiss: () -> Unit,
    onConfirm: (GroupMember) -> Unit,
) {
    member ?: return
    IMConfirmDialog(
        title = "转让群组",
        message = "转让给「${member.displayName}」后你将立即变为普通成员，且不可撤销。",
        confirmText = "转让",
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(member) },
    )
}

/**
 * 群管理项的编辑框/确认框（群名称 / 简介 / 公告 / 全员禁言）。
 *
 * 从 `GroupInfoHost` 拆出（CODING_STYLE §7②）：那个文件是接线层且贴着 600 行硬闸，
 * 而"改哪一项弹什么框、确认之后调哪个接口"是一块自洽的东西。
 *
 * **改群资料是整体替换**（PROTOCOL §11 明说）：只改名字也要把头像与简介原样带回去，
 * 否则会把它们清空。这条纪律就落在下面每个 `updateInfo` 调用里，别在别处再抄一遍。
 *
 * @param runManage 执行一次管理动作并把成败说给用户听（宿主提供：它持有 toast 与刷新）。
 */
@Composable
internal fun GroupManagePrompts(
    action: GroupManageAction?,
    info: GroupInfo,
    convId: String,
    client: IMClient,
    onDismiss: () -> Unit,
    runManage: (String, suspend () -> Unit) -> Unit,
) {
    val g = info
    when (action) {
        GroupManageAction.EditName -> IMTextPrompt(
            title = "群名称", initial = g.name, maxLen = 30,
            onDismiss = { onDismiss() },
            onConfirm = { v ->
                onDismiss()
                // 改群资料是**整体替换**：只改名字也要把头像和简介原样带回去，
                // 否则会把它们清空（PROTOCOL §11 明说整体替换）。
                runManage("修改群名") { client.groups.updateInfo(convId, v, g.avatarUrl, g.intro) }
            },
        )
        GroupManageAction.EditIntro -> IMTextPrompt(
            title = "群简介", initial = g.intro, maxLen = 200, multiline = true,
            onDismiss = { onDismiss() },
            onConfirm = { v ->
                onDismiss()
                runManage("修改群简介") { client.groups.updateInfo(convId, g.name, g.avatarUrl, v) }
            },
        )
        GroupManageAction.EditAnnouncement -> IMTextPrompt(
            title = "群公告", initial = g.announcement, maxLen = 500, multiline = true,
            hint = "留空即撤下公告。发布会在群里落一条系统消息。",
            onDismiss = { onDismiss() },
            onConfirm = { v ->
                onDismiss()
                runManage(if (v.isBlank()) "撤下公告" else "发布公告") {
                    client.groups.setAnnouncement(convId, v)
                }
            },
        )
        GroupManageAction.ToggleMuteAll -> {
            val on = GroupPermissions.isMuteActive(g.muteUntil)
            IMConfirmDialog(
                title = if (on) "解除全员禁言？" else "开启全员禁言？",
                message = if (on) "解除后所有成员都可以发言。"
                else "开启后只有群主和管理员可以发言，直到你手动解除。",
                confirmText = if (on) "解除" else "开启",
                destructive = !on,
                onDismiss = { onDismiss() },
                onConfirm = {
                    onDismiss()
                    // -1 = 永久（协议口径），0 = 解除
                    runManage(if (on) "解除全员禁言" else "开启全员禁言") {
                        client.groups.setMuteAll(convId, if (on) 0L else -1L)
                    }
                },
            )
        }
        null -> Unit
    }
}
