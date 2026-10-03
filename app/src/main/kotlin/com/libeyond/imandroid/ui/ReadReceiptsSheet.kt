package com.libeyond.imandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.ReadBy
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMCardSheet
import com.libeyond.imandroid.ui.screens.SegTabBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 已读详情（对齐 iOS `IMReadReceiptViewController`）：卡片弹层，顶部「已读 N / 未读 N」两个页签，
 * **页签切换、一次只看一个列表**（不是两个分段）。行 = 34dp 头像 + 名字 + 群主/管理员徽标；
 * 名字取「好友备注 > 群昵称 > uid」，不在成员表里的（刚退群）退到 uid。点行 = 关弹层再进该成员资料页。
 * 一次响应最多 2000 个 uid，普通 `LazyColumn` 够用，不分页。
 */
@Composable
fun ReadReceiptsSheet(
    readBy: ReadBy,
    nameOf: (String) -> String,
    avatarOf: (String) -> String,
    roleOf: (String) -> String?,
    onOpenUser: (String) -> Unit,
    onDismissed: () -> Unit,
) {
    val c = IMTheme.colors
    var tab by remember { mutableStateOf(0) }
    IMCardSheet(onDismissed = onDismissed) { sheet ->
        Text(
            stringResource(R.string.receipts_title), color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        )
        SegTabBar(
            titles = listOf(
                stringResource(R.string.receipts_tab_read, readBy.read.size),
                stringResource(R.string.receipts_tab_unread, readBy.unread.size),
            ),
            selected = tab, onSelect = { tab = it },
        )
        val uids = if (tab == 0) readBy.read else readBy.unread
        if (uids.isEmpty()) {
            Text(
                stringResource(if (tab == 0) R.string.receipts_empty_read else R.string.receipts_empty_unread),
                color = c.textTertiary, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(uids, key = { it }) { uid ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sheet.dismiss { onOpenUser(uid) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val name = nameOf(uid)
                        IMAvatar(displayName = name, seed = uid, size = 34.dp, avatarUrl = avatarOf(uid))
                        Spacer(Modifier.width(12.dp))
                        Text(name, color = c.textPrimary, fontSize = 15.5.sp, modifier = Modifier.weight(1f))
                        when (roleOf(uid)) {
                            GroupMember.ROLE_OWNER -> RoleBadge(stringResource(R.string.group_role_owner))
                            GroupMember.ROLE_ADMIN -> RoleBadge(stringResource(R.string.group_role_admin))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleBadge(text: String) {
    val c = IMTheme.colors
    Box { Text(text, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}
