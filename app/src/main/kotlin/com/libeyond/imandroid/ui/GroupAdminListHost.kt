package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.GroupAdminListScreen
import kotlinx.coroutines.launch

/**
 * 管理员页的接线（对齐 iOS `IMGroupAdminListViewController`）：列表 + 长按菜单（查看资料 / 撤销管理员，
 * iOS 的 `contextMenuConfigurationForRowAtIndexPath:`）+ 撤销二次确认（`confirmRevoke:`）。
 *
 * 菜单与确认框的状态**收在本组件里**：它们只可能从这一页打开，页面一关（含「我被撤销管理员」时
 * `GroupInfoHost` 强制收页）状态随之消失，不会留一个悬空的确认框。
 * 角色即权限，撤销**不做乐观更新**：成功后以服务端为准重拉群资料与成员首页。
 */
@Composable
internal fun GroupAdminListHost(
    client: IMClient,
    convId: String,
    info: GroupInfo,
    members: List<GroupMember>,
    myUid: String,
    onOpenMember: (GroupMember) -> Unit,
    onAdd: () -> Unit,
    onToast: (String) -> Unit,
    onInfo: (GroupInfo) -> Unit,
    refreshMembers: suspend () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<GroupMember?>(null) }
    var confirmRevoke by remember { mutableStateOf<GroupMember?>(null) }
    val isOwner = info.myRole == GroupMember.ROLE_OWNER

    GroupAdminListScreen(
        owner = members.firstOrNull { it.role == GroupMember.ROLE_OWNER },
        admins = members.filter { it.role == GroupMember.ROLE_ADMIN },
        // 群主可增删、管理员只读（同 im-web / iOS）
        canEdit = isOwner,
        // 点自己不进（那是「编辑资料」的事，同 iOS `openPeerDetail:`）
        onOpenMember = { m -> if (m.userId != myUid) onOpenMember(m) },
        // 菜单没有可选项（点的是自己、且不是可撤销的管理员行）就不弹，免得出一张只有标题的空菜单
        onMemberLongPress = { m -> if (m.userId != myUid || (isOwner && m.role == GroupMember.ROLE_ADMIN)) menuFor = m },
        onRevoke = { m -> confirmRevoke = m },
        onAdd = onAdd,
        onBack = onBack,
    )

    menuFor?.let { m ->
        ActionSheet(
            title = m.displayName,
            items = buildList {
                if (m.userId != myUid) add(SheetItem(stringResource(R.string.chat_header_view_profile)) { onOpenMember(m) })
                if (isOwner && m.role == GroupMember.ROLE_ADMIN) {
                    add(SheetItem(stringResource(R.string.group_member_action_revoke_admin), destructive = true) { confirmRevoke = m })
                }
            },
            onDismiss = { menuFor = null },
        )
    }
    confirmRevoke?.let { m ->
        IMConfirmDialog(
            title = stringResource(R.string.group_member_action_revoke_admin),
            message = stringResource(R.string.group_admin_list_revoke_confirm_message, m.displayName),
            confirmText = stringResource(R.string.group_admin_list_revoke_btn),
            onConfirm = {
                scope.launch {
                    onToast(revokeAdminText(client, convId, m.userId))
                    runCatching { client.groups.info(convId) }.onSuccess(onInfo)
                    refreshMembers()
                }
            },
            onDismiss = { confirmRevoke = null },
        )
    }
}
