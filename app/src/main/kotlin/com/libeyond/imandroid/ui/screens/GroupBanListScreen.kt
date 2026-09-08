package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.sdk.api.GroupBan
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 群黑名单（G2，群主/管理员）。对齐 iOS `IMGroupBanListViewController`。
 *
 * 「解除」是**放行**不是处罚，所以不做二次确认（与"踢人/转让"那类不可逆动作区分开）；
 * 但要显示是谁在什么时候拉黑的——否则管理员之间互相看不懂对方的处置。
 */
@Composable
internal fun GroupBanListScreen(
    bans: List<GroupBan>,
    loading: Boolean,
    busyUid: String,
    onUnban: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = "黑名单", onLeft = onBack)
        when {
            loading && bans.isEmpty() -> Empty("加载中…")
            bans.isEmpty() -> Empty("黑名单是空的")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(bans, key = { it.userId }) { b ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth().background(c.surface)
                                .padding(horizontal = d.space4, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IMAvatar(b.displayName, seed = b.userId, avatarUrl = b.avatarUrl, size = 40.dp)
                            Spacer(Modifier.width(d.space3))
                            Column(Modifier.weight(1f)) {
                                Text(b.displayName, color = c.textPrimary,
                                    style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append(if (b.isPermanent) "永久" else "冷却中")
                                        if (b.bannedAt > 0) append(" · ${TimeFormat.conversationTime(b.bannedAt)}")
                                    },
                                    color = c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Box(
                                Modifier.clip(RoundedCornerShape(14.dp)).background(c.subtleFill)
                                    .clickable(enabled = busyUid != b.userId) { onUnban(b.userId) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text("解除", color = c.accent, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(start = 68.dp)
                            .height(0.5.dp).background(c.separator))
                    }
                }
            }
        }
    }
}

/**
 * 管理员列表（对齐 iOS `IMGroupAdminListViewController`）。
 * **群主可增删、管理员只读**——判据走 [com.libeyond.imandroid.data.GroupPermissions.canSetRole]，
 * 由 Host 传进来，本组件不自己判。
 */
@Composable
internal fun GroupAdminListScreen(
    admins: List<GroupMember>,
    canEdit: Boolean,
    busyUid: String,
    onRevoke: (GroupMember) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(
            title = "管理员",
            onLeft = onBack,
            actionText = if (canEdit) "添加" else "",
            onAction = if (canEdit) onAdd else null,
        )
        if (admins.isEmpty()) {
            Empty("还没有设置管理员")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(admins, key = { it.userId }) { m ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth().background(c.surface)
                                .padding(horizontal = d.space4, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IMAvatar(m.displayName, seed = m.userId, avatarUrl = m.avatarUrl, size = 40.dp)
                            Spacer(Modifier.width(d.space3))
                            Text(m.displayName, color = c.textPrimary,
                                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (canEdit) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(14.dp)).background(c.subtleFill)
                                        .clickable(enabled = busyUid != m.userId) { onRevoke(m) }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text("撤销", color = c.danger, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(start = 68.dp)
                            .height(0.5.dp).background(c.separator))
                    }
                }
            }
        }
        Footnote("管理员可审批入群、禁言与移出普通成员，但不能设置管理员或转让群组。")
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = IMTheme.colors.textTertiary, style = MaterialTheme.typography.bodyMedium)
    }
}
