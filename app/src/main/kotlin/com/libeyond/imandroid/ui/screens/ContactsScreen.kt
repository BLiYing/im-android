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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Contact
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.GlobalSearch
import com.libeyond.imandroid.data.RecentContacts
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.TopBarCircleButton
import com.libeyond.imandroid.ui.components.UnreadCapsule
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 通讯录首页（M2.5；2026-11-13 改版，`../IMServer/docs/design/ORG_DIRECTORY_DESIGN.md` §7）。
 *
 * 结构：搜索 + **顶部入口行**（群聊 / 新的朋友 / 我的好友）+「最近联系」分区。
 * 好友 A–Z 列表不在这一页——整体搬进了 [FriendListScreen]（入口「我的好友」）。
 * **不把待确认段内联在这里**——2026-09-05 三端统一移除了那种排法。
 *
 * 入口图标底色对齐 iOS 的 `entryColors`（群聊-绿 / 新的朋友-青 / 我的好友-蓝）。
 * 公众号 / 服务号入口 2026-11-03 起隐藏（功能未做，三端同步）；「组织通讯录」入口是后面的阶段（设计稿 §8）。
 */
@Composable
fun ContactsScreen(
    /** 已成立的好友：搜索用；人数另由 [friendCount] 给。 */
    friends: List<FriendEntry>,
    pendingCount: Int,
    onOpenNewFriends: () -> Unit,
    /** 右上角「添加朋友」。 */
    onAddFriend: () -> Unit,
    onOpenGroups: () -> Unit,
    /** 入口「我的好友」。 */
    onOpenFriends: () -> Unit,
    /** 搜索结果里点好友（进资料页）。 */
    onOpenFriend: (FriendEntry) -> Unit,
    /** 「我的好友」行的右值；null = 好友表还没拿到（不画数字，免得先闪一个 0）。 */
    friendCount: Int? = friends.size,
    /** 「最近联系」（判据 [RecentContacts.pick]）；空 = 整段不出现。 */
    recent: List<ConversationEntity> = emptyList(),
    /** 点最近联系的行：**直接进聊天**（不经资料页）。 */
    onOpenRecent: (ConversationEntity) -> Unit = {},
    /** 我加入的群（搜索用；没拉到时为空）。 */
    groups: List<GroupInfo> = emptyList(),
    onOpenGroup: (GroupInfo) -> Unit = {},
    /** 搜索无结果时「搜索用户「x」」入口：带关键词进加好友页。 */
    onSearchUser: (String) -> Unit = {},
    /** 搜索词由 Host 持有：点结果进二级页再返回，本页会离开组合，词留在这里才不丢（iOS 搜索栏随 VC 常驻）。 */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    /** 列表滚动位置由 Host 持有：进二级页再返回，本页会离开组合，状态放这里才不回到顶部。 */
    listState: LazyListState = rememberLazyListState(),
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    // **systemBarsPadding 不能漏**：Tab 根页面自己顶到屏幕边缘，不加这一句标题会压到状态栏上去
    // （2026-09-08 用户报的就是这个：「通讯录」四个字骑在时间和信号图标上）。
    // 会话列表与「我」页早就有，唯独这一页漏了。
    Column(modifier = Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        // 标题居中 + 右上角圆形「添加朋友」钮，对齐 iOS（`person.badge.plus` 圆钮 → `IMUserSearchViewController`）。
        // 此前是左对齐的大标题 + 一枚主色裸放大镜，点进去标题叫「找人」（2026-09-15 用户报）
        IMTopBar(
            title = stringResource(R.string.contacts_title),
            right = {
                TopBarCircleButton(
                    icon = Lucide.UserPlus,
                    description = stringResource(R.string.contacts_search_title),
                    onClick = onAddFriend,
                )
            },
        )

        // 搜索：本地联系人 + 群聊（备注/昵称/账号/群名），分组展示；无结果给「按账号搜索用户」入口
        IMSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.search_contacts_placeholder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
        )

        // 不提前 return：搜索 / 清空搜索在同一处分支渲染，listState 不会因搜索而重建
        if (query.isNotBlank()) {
            ContactsSearchResults(
                keyword = query.trim(),
                friends = GlobalSearch.friendHits(friends, query),
                groups = GlobalSearch.groupHits(groups, query),
                onOpenFriend = onOpenFriend,
                onOpenGroup = onOpenGroup,
                onSearchUser = { onSearchUser(query.trim()) },
            )
        } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "entries") {
                EntryRow(Lucide.Users, ENTRY_GROUPS, stringResource(R.string.common_group_chat), onOpenGroups)
                EntryRow(
                    Lucide.UserPlus, ENTRY_NEW_FRIENDS,
                    stringResource(R.string.friend_requests_title), onOpenNewFriends, badge = pendingCount,
                )
                EntryRow(
                    Lucide.Contact, ENTRY_MY_FRIENDS,
                    stringResource(R.string.contacts_entry_my_friends), onOpenFriends,
                    value = friendCount?.toString().orEmpty(),
                )
                // 公众号 / 服务号入口暂时隐藏（2026-11-03，功能未做，三端同步）。
                // 恢复时加回 Megaphone(#FF9500) / Headphones(#007AFF) 两行。
            }
            // 最近联系：一个都没有时**整段不出现**（连标题一起）
            if (recent.isNotEmpty()) {
                item(key = "recent-h") { ContactSectionLabel(stringResource(R.string.contacts_recent_title)) }
                // 行只有头像 + 显示名：不带预览和时间，无左滑
                items(recent, key = { "r-" + it.convId }) { conv ->
                    FriendRow(
                        name = RecentContacts.nameOf(conv).ifBlank { DisplayName.UNNAMED },
                        seed = conv.peerUid,
                        avatarUrl = conv.avatarUrl,
                        onClick = { onOpenRecent(conv) },
                    )
                }
            }
        }
    }
}

// 入口图标底色，逐条对齐 iOS `entryColors`（systemGreen / systemTeal / systemBlue）。
// **不跟主题主色走、也不随深浅色变**：这几条是靠颜色区分的，全刷成 accent 就退回"几个一样的绿圆圈"。
private val ENTRY_GROUPS = Color(0xFF34C759)
private val ENTRY_NEW_FRIENDS = Color(0xFF30B0C7)
private val ENTRY_MY_FRIENDS = Color(0xFF007AFF)

/**
 * 顶部入口行（高 64：上下 12 + 40 圆形图标）。行尾依次是：角标 / 右值 → 箭头。
 *
 * @param badge 蓝色角标数字（0 不画）
 * @param value 右值小字（空 = 不画；目前只有「我的好友」= 好友数）
 */
@Composable
private fun EntryRow(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    onClick: () -> Unit,
    badge: Int = 0,
    value: String = "",
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
        Text(
            title, color = c.textPrimary, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        // 角标三端统一蓝（2026-09-15 用户要求，原为 danger 红）；共用 UnreadCapsule（0 不画，>99 → 99+）
        UnreadCapsule(badge)
        if (value.isNotEmpty()) {
            Text(
                value, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = ENTRY_VALUE_MAX_WIDTH),
            )
        }
        // 所有入口行都带箭头（与 iOS / Web 对齐）
        Spacer(Modifier.width(ENTRY_TRAILING_GAP))
        Image(
            Lucide.ChevronRight, null, Modifier.size(ENTRY_CHEVRON_SIZE),
            colorFilter = ColorFilter.tint(c.textTertiary),
        )
    }
}

private val ENTRY_VALUE_MAX_WIDTH = 140.dp
private val ENTRY_TRAILING_GAP = 6.dp
private val ENTRY_CHEVRON_SIZE = 18.dp

/** 通讯录搜索结果：联系人 / 群聊两组；两组都空时只剩「搜索用户」入口（复用加好友页）。 */
@Composable
private fun ContactsSearchResults(
    keyword: String,
    friends: List<FriendEntry>,
    groups: List<GroupInfo>,
    onOpenFriend: (FriendEntry) -> Unit,
    onOpenGroup: (GroupInfo) -> Unit,
    onSearchUser: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    LazyColumn(Modifier.fillMaxSize()) {
        if (friends.isNotEmpty()) {
            item("h-friend") { ContactSectionLabel(stringResource(R.string.search_section_contacts)) }
            items(friends, key = { "f-" + it.userId }) { f -> FriendRow(f, onClick = { onOpenFriend(f) }) }
        }
        if (groups.isNotEmpty()) {
            item("h-group") { ContactSectionLabel(stringResource(R.string.common_group_chat)) }
            items(groups, key = { "g-" + it.convId }) { g ->
                // 群行与好友行同一套几何（头像 40、分隔线从 68 起），共用 FriendRow
                FriendRow(name = g.name, seed = g.convId, avatarUrl = g.avatarUrl, onClick = { onOpenGroup(g) })
            }
        }
        if (friends.isEmpty() && groups.isEmpty()) {
            item("empty") {
                Text(
                    stringResource(R.string.search_no_matches), color = c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            item("user") {
                Row(
                    modifier = Modifier.fillMaxWidth().background(c.pageBackground).clickable(onClick = onSearchUser)
                        .padding(horizontal = d.space4, vertical = d.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(ENTRY_NEW_FRIENDS),
                        contentAlignment = Alignment.Center,
                    ) { Image(Lucide.UserPlus, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White)) }
                    Spacer(Modifier.width(d.space3))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.search_user_row_title, keyword), color = c.textPrimary,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.search_user_row_subtitle), color = c.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
