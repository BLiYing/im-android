package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.UserPlus
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 通讯录（M2.5）。
 *
 * 结构对齐 iOS/Web：「新的朋友」独立入口行（带待确认数）+ 好友列表。
 * **不再把待确认段内联在好友列表上方**——2026-09-05 三端统一移除了那种排法。
 */
@Composable
fun ContactsScreen(
    friends: List<FriendEntry>,
    pendingCount: Int,
    onOpenNewFriends: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFriend: (FriendEntry) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(modifier = Modifier.fillMaxSize().background(c.groupedBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(c.surface)
                .padding(horizontal = d.space4, vertical = d.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("通讯录", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Image(
                imageVector = Lucide.Search,
                contentDescription = "找人",
                modifier = Modifier.size(22.dp).clickable { onOpenSearch() },
                colorFilter = ColorFilter.tint(c.accent),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                EntryRow(
                    icon = { Image(Lucide.UserPlus, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(c.onAccent)) },
                    title = "新的朋友",
                    badge = pendingCount,
                    onClick = onOpenNewFriends,
                )
                SectionLabel("好友")
            }
            if (friends.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("还没有好友，点右上角搜索添加", color = c.textTertiary,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(friends, key = { it.userId }) { f -> FriendRow(f, onClick = { onOpenFriend(f) }) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val c = IMTheme.colors
    Text(
        text = text,
        color = c.textTertiary,
        style = MaterialTheme.typography.bodyMedium,
        // 分组标题左边缘与卡片左边缘对齐（UI_COLOR §4）
        modifier = Modifier.padding(start = IMTheme.dimens.space4, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    title: String,
    badge: Int,
    onClick: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().background(c.pageBackground).clickable { onClick() }
            .padding(horizontal = d.space4, vertical = d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(d.space3))
        Text(title, color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        if (badge > 0) {
            Box(
                modifier = Modifier.clip(CircleShape).background(c.danger)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text(badge.toString(), color = c.onAccent, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun FriendRow(f: FriendEntry, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().background(c.pageBackground).clickable { onClick() }
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(displayName = f.displayName, seed = f.userId, avatarUrl = f.avatarUrl, size = 40.dp)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(
                f.displayName, color = c.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // 标识行为空时**整行隐藏**——不显示「用户名：未设置」，更不回退内部 ID
            if (f.handle.isNotEmpty()) {
                Text(f.handle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (f.blocked) Text("已拉黑", color = c.textTertiary, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = 68.dp).background(c.separator))
}
