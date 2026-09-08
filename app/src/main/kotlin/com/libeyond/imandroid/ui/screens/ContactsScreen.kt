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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Headphones
import com.composables.icons.lucide.Megaphone
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 通讯录（M2.5）。
 *
 * 结构对齐 iOS `IMContactsViewController`：**四条顶部入口** + 好友列表。
 * **不再把待确认段内联在好友列表上方**——2026-09-05 三端统一移除了那种排法。
 *
 * 入口顺序与图标底色逐条照抄 iOS 的 `entries` / `entryColors`
 * （群聊-绿 / 新的朋友-青 / 公众号-橙 / 服务号-蓝）：本端 2026-09-08 之前只有两条，
 * 且第二条是「发起群聊」——那是**动作**不是入口，iOS 把建群放在群列表页的右上角 `+`。
 * 公众号/服务号两端都还没做，但**入口先在**（点了给"开发中"），
 * 否则两端的通讯录首屏一眼就不是同一个 App。
 */
@Composable
fun ContactsScreen(
    friends: List<FriendEntry>,
    pendingCount: Int,
    onOpenNewFriends: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGroups: () -> Unit,
    /** 尚未实现的入口（公众号/服务号）——由 Host 弹「开发中」。 */
    onComingSoon: (String) -> Unit,
    onOpenFriend: (FriendEntry) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    // **systemBarsPadding 不能漏**：Tab 根页面自己顶到屏幕边缘，不加这一句标题会压到状态栏上去
    // （2026-09-08 用户报的就是这个：「通讯录」四个字骑在时间和信号图标上）。
    // 会话列表与「我」页早就有，唯独这一页漏了。
    Column(modifier = Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
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
                EntryRow(Lucide.Users, ENTRY_GROUPS, "群聊", 0, onOpenGroups)
                EntryRow(Lucide.UserPlus, ENTRY_NEW_FRIENDS, "新的朋友", pendingCount, onOpenNewFriends)
                EntryRow(Lucide.Megaphone, ENTRY_OFFICIAL, "公众号", 0) { onComingSoon("公众号") }
                EntryRow(Lucide.Headphones, ENTRY_SERVICE, "服务号", 0) { onComingSoon("服务号") }
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

// 入口图标底色，逐条对齐 iOS `entryColors`（systemGreen / systemTeal / systemOrange / systemBlue）。
// **不跟主题主色走**：这四条是靠颜色区分的，全刷成 accent 就退回"四个一样的绿圆圈"。
private val ENTRY_GROUPS = Color(0xFF34C759)
private val ENTRY_NEW_FRIENDS = Color(0xFF30B0C7)
private val ENTRY_OFFICIAL = Color(0xFFFF9500)
private val ENTRY_SERVICE = Color(0xFF007AFF)

@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
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
            modifier = Modifier.size(40.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
        }
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
