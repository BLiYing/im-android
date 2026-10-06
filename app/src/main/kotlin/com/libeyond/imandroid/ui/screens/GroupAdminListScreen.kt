package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.UserPlus
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 管理员列表（对齐 iOS `IMGroupAdminListViewController`）。
 *
 * 结构逐段对齐 iOS：**「群主」只读一行** → **「管理员 · N」**（群主多一行「添加管理员」，
 * 没有管理员时给「还没有管理员」占位）→ 按身份换的脚注。点成员进资料页，**左滑**（仅群主、仅管理员行）
 * 或长按菜单撤销——撤销都要二次确认（[onRevoke] 由 Host 弹确认框，本组件不直接执行）。
 *
 * **群主可增删、管理员只读**——判据走 [com.libeyond.imandroid.data.GroupPermissions.canSetRole]，
 * 由 Host 以 [canEdit] 传进来，本组件不自己判。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupAdminListScreen(
    owner: GroupMember?,
    admins: List<GroupMember>,
    canEdit: Boolean,
    onOpenMember: (GroupMember) -> Unit,
    onMemberLongPress: (GroupMember) -> Unit,
    onRevoke: (GroupMember) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.group_role_admin), onLeft = onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            if (owner != null) {
                item(key = "owner-h") { SectionHeader(stringResource(R.string.group_role_owner)) }
                item(key = "owner") {
                    Box(Modifier.padding(horizontal = d.space4).clip(RoundedCornerShape(d.radiusCard))) {
                        MemberRow(owner, onClick = { onOpenMember(owner) }, onLongClick = { onMemberLongPress(owner) }, background = c.cardBackground, divider = false)
                    }
                }
            }
            item(key = "admins-h") { SectionHeader(stringResource(R.string.group_admin_list_count_title, admins.size)) }
            item(key = "admins") {
                Column(Modifier.padding(horizontal = d.space4).clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground)) {
                    if (canEdit) LeadingEntryRow(
                        icon = { AccentLineIcon(Lucide.UserPlus) },
                        title = stringResource(R.string.group_admin_picker_title),
                        onClick = onAdd, showChevron = true, divider = true,
                    )
                    if (admins.isEmpty()) {
                        Text(
                            stringResource(R.string.group_admin_list_empty), color = c.textSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = 14.dp),
                        )
                    }
                    admins.forEachIndexed { i, m ->
                        val line = i < admins.size - 1
                        if (canEdit) {
                            SwipeToRevokeRow(onRevoke = { onRevoke(m) }) {
                                MemberRow(m, onClick = { onOpenMember(m) }, onLongClick = { onMemberLongPress(m) }, background = c.cardBackground, divider = line)
                            }
                        } else {
                            MemberRow(m, onClick = { onOpenMember(m) }, onLongClick = { onMemberLongPress(m) }, background = c.cardBackground, divider = line)
                        }
                    }
                }
            }
            item(key = "footer") {
                // 与 iOS `titleForFooterInSection:` 同一份分支：非群主 / 群主有管理员 / 群主还没有管理员
                Footnote(
                    stringResource(
                        when {
                            !canEdit -> R.string.group_admin_list_owner_only_note
                            admins.isNotEmpty() -> R.string.group_admin_list_footer_can_revoke
                            else -> R.string.group_manage_permission_note
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, color = IMTheme.colors.textTertiary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = IMTheme.dimens.space4 + 4.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * 左滑露出红色「撤销」（对齐 iOS 的 trailing swipe action）。**滑完就弹回**：真正的成败取决于二次确认后的请求，
 * 不能让行停在「已撤销」的位置上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRevokeRow(onRevoke: () -> Unit, content: @Composable () -> Unit) {
    val c = IMTheme.colors
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) onRevoke()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(c.danger).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(R.string.group_admin_list_revoke_btn), color = c.onAccent, style = MaterialTheme.typography.bodyLarge)
            }
        },
        modifier = Modifier.background(c.cardBackground),
    ) { Box(Modifier.background(c.cardBackground)) { content() } }
}
