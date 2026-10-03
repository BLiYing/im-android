package com.libeyond.imandroid.ui
import com.libeyond.imandroid.ui.screens.GroupManageAction
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.GroupTextSheet
import com.libeyond.imandroid.ui.components.IMTextPrompt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
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
            title = stringResource(R.string.chat_detail_clear_history_confirm_title),
            // **「仅清空本机」这半句不能省**：群聊里少了它，用户会以为自己替全群删了历史
            message = stringResource(R.string.chat_detail_clear_history_message_group),
            confirmText = stringResource(R.string.chat_clear_ok),
            onConfirm = onClearHistory,
            onDismiss = onDismiss,
        )
        DetailMoreAction.LeaveGroup -> IMConfirmDialog(
            title = stringResource(R.string.chat_detail_leave_group_confirm_title, groupName),
            message = stringResource(R.string.chat_detail_leave_group_message),
            confirmText = stringResource(R.string.group_info_leave_confirm),
            onConfirm = onLeave,
            onDismiss = onDismiss,
        )
        DetailMoreAction.DissolveGroup -> IMConfirmDialog(
            title = stringResource(R.string.chat_detail_dissolve_confirm_title, groupName),
            message = stringResource(R.string.chat_detail_dissolve_confirm_message),
            confirmText = stringResource(R.string.common_delete),
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
        title = stringResource(R.string.group_manage_transfer_group),
        message = stringResource(R.string.group_info_transfer_confirm_message, member.displayName),
        confirmText = stringResource(R.string.group_transfer_owner_confirm),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(member) },
    )
}

/** 与服务端 `group.MaxMemberNicknameLen`（20）/ `conversation.MaxConvRemarkLen`（30）对齐——此前昵称这里写成 30，超 20 的会被服务端拒。 */
private const val MY_NICKNAME_MAX_LEN = 20
private const val GROUP_REMARK_MAX_LEN = 30

/**
 * 群资料页设置区的两个编辑弹窗（我在本群的昵称 / 群备注）+ 群公告·群简介只读全文。
 * 从 `GroupInfoHost` 拆出（同上、CODING_STYLE §7②）：这几个弹层只认「开不开、初始值、
 * 确认后回调什么」，不需要知道调用方怎么落地。
 */
@Composable
internal fun GroupInfoSettingsDialogs(
    settings: GroupInfoSettingsState,
    myNickname: String,
    /** 群备注输入框的占位＝群名（对齐 iOS 的 placeholder）。 */
    groupName: String,
    onConfirmMyNickname: (String) -> Unit,
) {
    if (settings.editingMyNickname) {
        IMTextPrompt(
            title = stringResource(R.string.chat_detail_my_group_nickname), initial = myNickname,
            maxLen = MY_NICKNAME_MAX_LEN,
            hint = stringResource(R.string.group_info_my_nickname_hint),
            onDismiss = settings::dismissMyNicknameEditor,
            onConfirm = { v -> settings.dismissMyNicknameEditor(); onConfirmMyNickname(v) },
        )
    }
    if (settings.editingRemark) {
        IMTextPrompt(
            title = stringResource(R.string.chat_detail_group_remark), initial = settings.remark, maxLen = GROUP_REMARK_MAX_LEN,
            label = groupName,
            hint = stringResource(R.string.group_info_remark_hint),
            onDismiss = settings::dismissRemarkEditor,
            onConfirm = { v -> settings.setRemark(v); settings.dismissRemarkEditor() },
        )
    }
    settings.notice?.let { n -> GroupTextSheet(n.title, n.body, onDismiss = settings::dismissNotice, subtitle = n.subtitle) }
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
            title = stringResource(R.string.group_create_name_label), initial = g.name, maxLen = 30,
            onDismiss = { onDismiss() },
            onConfirm = { v ->
                onDismiss()
                // 改群资料是**整体替换**：只改名字也要把头像和简介原样带回去，
                // 否则会把它们清空（PROTOCOL §11 明说整体替换）。
                runManage(Str.s(R.string.group_info_rename_title)) { client.groups.updateInfo(convId, v, g.avatarUrl, g.intro) }
            },
        )
        GroupManageAction.EditIntro -> IMTextPrompt(
            title = stringResource(R.string.group_text_intro), initial = g.intro, maxLen = 200, multiline = true,
            onDismiss = { onDismiss() },
            onConfirm = { v ->
                onDismiss()
                runManage(Str.s(R.string.group_info_edit_intro_label)) { client.groups.updateInfo(convId, g.name, g.avatarUrl, v) }
            },
        )
        GroupManageAction.EditAnnouncement -> IMTextPrompt(
            title = stringResource(R.string.group_text_announcement), initial = g.announcement, maxLen = 500, multiline = true,
            hint = stringResource(R.string.group_manage_announcement_hint),
            clearActionText = stringResource(R.string.group_ops_announcement_retract),
            onDismiss = { onDismiss() },
            onConfirm = { v ->
                onDismiss()
                runManage(
                    if (v.isBlank()) Str.s(R.string.group_ops_announcement_retract) else Str.s(R.string.group_manage_announcement_publish_label),
                ) {
                    client.groups.setAnnouncement(convId, v)
                }
            },
        )
        GroupManageAction.ToggleMuteAll -> {
            val on = GroupPermissions.isMuteActive(g.muteUntil)
            IMConfirmDialog(
                title = stringResource(if (on) R.string.group_manage_mute_all_off_confirm_title else R.string.group_manage_mute_all_on_confirm_title),
                message = stringResource(
                    if (on) R.string.group_manage_mute_all_off_confirm_message else R.string.group_manage_mute_all_on_confirm_message,
                ),
                confirmText = stringResource(if (on) R.string.group_manage_mute_all_off_confirm else R.string.group_manage_mute_all_on_confirm),
                destructive = !on,
                onDismiss = { onDismiss() },
                onConfirm = {
                    onDismiss()
                    // -1 = 永久（协议口径），0 = 解除
                    runManage(Str.s(if (on) R.string.group_manage_mute_all_off_label else R.string.group_manage_mute_all_on_label)) {
                        client.groups.setMuteAll(convId, if (on) 0L else -1L)
                    }
                },
            )
        }
        null -> Unit
    }
}

/**
 * 「我不再是群主/管理员」的**当场响应**（对齐 iOS：群管理页 / 管理员页重拉后发现 `!canManage` 就 pop 并吐
 * 「你已不是群主或管理员」；详情页的「群管理」入口随身份重算消失）。
 *
 * 此前 Android 只有详情页入口会消失，管理页 / 管理员页 / 编辑框开着时仍留在原地，还能点进去被服务端拒。
 * `wasManager` 记「上一份群资料里我是不是管理层」：**只在 true→false 那一刻触发**，
 * 普通成员进页、或首次加载出结果时不会误弹。[onLost] 里关页面、关弹层、吐司。
 */
@Composable
internal fun OnManagerRoleLost(isManager: Boolean, convKey: String, onLost: () -> Unit) {
    var wasManager by remember(convKey) { mutableStateOf(isManager) }
    LaunchedEffect(isManager) {
        if (wasManager && !isManager) onLost()
        wasManager = isManager
    }
}
