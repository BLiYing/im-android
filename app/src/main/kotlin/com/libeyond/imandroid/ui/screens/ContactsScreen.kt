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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.libeyond.imandroid.data.ContactSection
import com.libeyond.imandroid.data.FriendAction
import com.libeyond.imandroid.data.FriendActions
import kotlinx.coroutines.launch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Headphones
import com.composables.icons.lucide.Megaphone
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.data.GlobalSearch
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.ui.components.TopBarCircleButton
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
    /** 右上角「添加朋友」。 */
    onAddFriend: () -> Unit,
    onOpenGroups: () -> Unit,
    /** 尚未实现的入口（公众号/服务号）——由 Host 弹「开发中」。 */
    onComingSoon: (String) -> Unit,
    onOpenFriend: (FriendEntry) -> Unit,
    /** 左滑动作（删除 / 拉黑 / 解除拉黑）。由 Host 执行并负责二次确认。 */
    onFriendAction: (FriendEntry, FriendAction) -> Unit = { _, _ -> },
    /** 我加入的群（搜索用；没拉到时为空）。 */
    groups: List<GroupInfo> = emptyList(),
    onOpenGroup: (GroupInfo) -> Unit = {},
    /** 搜索无结果时「搜索用户「x」」入口：带关键词进加好友页。 */
    onSearchUser: (String) -> Unit = {},
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
        var query by remember { mutableStateOf("") }
        IMSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.search_contacts_placeholder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
        )
        if (query.isNotBlank()) {
            ContactsSearchResults(
                keyword = query.trim(),
                friends = GlobalSearch.friendHits(friends, query),
                groups = GlobalSearch.groupHits(groups, query),
                onOpenFriend = onOpenFriend,
                onOpenGroup = onOpenGroup,
                onSearchUser = { onSearchUser(query.trim()) },
            )
            return@Column
        }

        // 按拼音首字母分组（判据在 ContactSection，与 iOS IMContactSectionIndex 同一套规则）
        val groups = remember(friends) { ContactSection.group(friends) { it.displayName } }
        val titles = remember(groups) { ContactSection.titlesOf(groups) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // 每个字母组的**首项在列表中的下标**（索引尺跳组要用）。判据抽在 ContactSection 里有单测：
        // 顶部入口占 1 项，所以字母组从 1 开始——iOS 那侧同样是 `+1` 偏移绕过入口区。
        val groupStarts = remember(groups) {
            ContactSection.groupStartIndices(groups.map { it.items.size }, leadingItems = ENTRY_ITEMS)
        }
        // 当前敞着的那一行（null = 没有）。**上提到这里**是因为 iOS 的两条行为都要全局视角：
        // 滑开第二行时第一行自动收起、敞着的行点内容只收起不进详情。
        var openedId by remember { mutableStateOf<String?>(null) }

        val officialLabel = stringResource(R.string.contacts_entry_official_account)
        val serviceLabel = stringResource(R.string.contacts_entry_service_account)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // 四个入口**共占一个 LazyColumn item**——ENTRY_ITEMS 记的就是这个 1，
                // 索引尺的偏移全靠它。以后在字母组之前再插 item，这里和 groupStartIndices 一起改
                item {
                    EntryRow(Lucide.Users, ENTRY_GROUPS, stringResource(R.string.common_group_chat), 0, onOpenGroups)
                    EntryRow(
                        Lucide.UserPlus, ENTRY_NEW_FRIENDS,
                        stringResource(R.string.friend_requests_title), pendingCount, onOpenNewFriends,
                    )
                    EntryRow(Lucide.Megaphone, ENTRY_OFFICIAL, officialLabel, 0) { onComingSoon(officialLabel) }
                    EntryRow(Lucide.Headphones, ENTRY_SERVICE, serviceLabel, 0) { onComingSoon(serviceLabel) }
                }
                if (friends.isEmpty()) {
                    item {
                        SectionLabel(stringResource(R.string.contacts_friends_section_label))
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.contacts_empty), color = c.textTertiary,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                groups.forEach { g ->
                    // 组头 key 用**拼接**：模板串里的 $ 一旦被转义写成字面量，所有组头就共用同一个 key，
                    // 第二个组头当场崩（2026-09-09 真机实测：`Key ... was already used`）。
                    // 单测测不到——它是 LazyColumn 测量期才抛的，只有真机跑到第二组才炸
                    item(key = "h-" + g.key) { SectionLabel(g.key) }
                    items(g.items, key = { it.userId }) { f ->
                        SwipeActionRow(
                            actions = FriendActions.availableFor(f.blocked).map { a ->
                                SwipeAction(
                                    label = a.label,
                                    // 颜色逐条对齐 iOS `block.backgroundColor = blocked ? systemGreen : systemGray`，
                                    // 删除走 destructive 红。**三个都得是不透明色**——半透明的中性填充
                                    // 压白字只有 1.2:1 对比度，浅色模式下整格看不见
                                    background = when (a) {
                                        FriendAction.Delete -> c.danger
                                        FriendAction.Unblock -> c.swipePositive
                                        FriendAction.Block -> c.swipeNeutral
                                    },
                                    onClick = { onFriendAction(f, a) },
                                )
                            },
                            opened = openedId == f.userId,
                            onOpenedChange = { open -> openedId = if (open) f.userId else null },
                            // 敞着的行点内容只**收起**，不进资料页（同 iOS 的 UITableView）
                        ) { FriendRow(f, onClick = { if (openedId == f.userId) openedId = null else onOpenFriend(f) }) }
                    }
                }
            }
            // 索引尺：没有好友时 titles 为空，组件自己整条不画（同 iOS 回 nil）
            ContactIndexBar(
                titles = titles,
                onPick = { i ->
                    groupStarts.getOrNull(i)?.let { scope.launch { listState.scrollToItem(it) } }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
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
/** 四个顶部入口**共占一个** LazyColumn item。索引尺的组下标偏移量就是它。 */
private const val ENTRY_ITEMS = 1

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
            // 角标三端统一蓝（2026-09-15 用户要求，原为 danger 红）；高/最小宽与会话列表 UnreadBadge 同一令牌，
            // 内容居中——个位数是正圆，两位数才拉成胶囊（此前只有 padding、没有最小宽与居中）。
            Box(
                modifier = Modifier
                    .height(d.unreadBadgeHeight)
                    .widthIn(min = d.unreadBadgeHeight)
                    .clip(CircleShape)
                    .background(c.unreadBadge)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badge > 99) "99+" else badge.toString(),
                    color = c.onAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
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
        if (f.blocked) Text(stringResource(R.string.common_blocked), color = c.textTertiary, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = 68.dp).background(c.separator))
}

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
            item("h-friend") { SectionLabel(stringResource(R.string.search_section_contacts)) }
            items(friends, key = { "f-" + it.userId }) { f -> FriendRow(f, onClick = { onOpenFriend(f) }) }
        }
        if (groups.isNotEmpty()) {
            item("h-group") { SectionLabel(stringResource(R.string.common_group_chat)) }
            items(groups, key = { "g-" + it.convId }) { g ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(c.pageBackground).clickable { onOpenGroup(g) }
                        .padding(horizontal = d.space4, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IMAvatar(displayName = g.name, seed = g.convId, avatarUrl = g.avatarUrl, size = 40.dp)
                    Spacer(Modifier.width(d.space3))
                    Text(
                        g.name, color = c.textPrimary, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = 68.dp).background(c.separator))
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
