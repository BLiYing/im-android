package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ContactSection
import com.libeyond.imandroid.data.FriendAction
import com.libeyond.imandroid.data.FriendActions
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * 「我的好友」（通讯录首页的入口行点进来，`ContactsPage.Friends`；ORG_DIRECTORY_DESIGN §7）。
 *
 * 原通讯录首页的好友列表**原样搬入**：A–Z 分组 + 右侧索引尺 + 左滑删除 / 拉黑。
 * 点行进资料页（不直接进聊天，三端统一的微信式口径）。
 */
@Composable
fun FriendListScreen(
    friends: List<FriendEntry>,
    onOpenFriend: (FriendEntry) -> Unit,
    /** 左滑动作（删除 / 拉黑 / 解除拉黑）。由 Host 执行并负责二次确认。 */
    onFriendAction: (FriendEntry, FriendAction) -> Unit,
    onBack: () -> Unit,
    /** 列表滚动位置由 Host 持有：进好友资料再返回，本页会离开组合，状态放这里才不回到顶部。 */
    listState: LazyListState = rememberLazyListState(),
) {
    val c = IMTheme.colors
    val scope = rememberCoroutineScope()
    // 按拼音首字母分组（判据在 ContactSection，与 iOS IMContactSectionIndex 同一套规则）
    val sections = remember(friends) { ContactSection.group(friends) { it.displayName } }
    val titles = remember(sections) { ContactSection.titlesOf(sections) }
    // 每个字母组的**首项在列表中的下标**（索引尺跳组要用）。判据抽在 ContactSection 里有单测
    val groupStarts = remember(sections) {
        ContactSection.groupStartIndices(sections.map { it.items.size }, leadingItems = 0) // 本页列表从第一个组头开始，没有前置 item
    }
    // 当前敞着的那一行（null = 没有）。**上提到这里**是因为 iOS 的两条行为都要全局视角：
    // 滑开第二行时第一行自动收起、敞着的行点内容只收起不进详情。
    var openedId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.contacts_entry_my_friends), onLeft = onBack)

        if (friends.isEmpty()) {
            Text(
                stringResource(R.string.contacts_friends_empty), color = c.textTertiary,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().padding(32.dp),
            )
            return@Column
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                sections.forEach { g ->
                    // 组头 key 用**拼接**：模板串里的 $ 一旦被转义写成字面量，所有组头就共用同一个 key，
                    // 第二个组头当场崩（2026-09-09 真机实测：`Key ... was already used`）。
                    // 单测测不到——它是 LazyColumn 测量期才抛的，只有真机跑到第二组才炸
                    item(key = "h-" + g.key) { ContactSectionLabel(g.key) }
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
            // 索引尺：与列表同一个容器、贴右居中
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
