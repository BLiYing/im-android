package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ContactSection
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.GroupNameDefault
import com.libeyond.imandroid.data.GroupSelectAll
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * 建群**第一步：选好友**（搜索 + 拼音 A–Z 分组 + 右侧索引尺，复用 `ContactSection`/`ContactIndexBar`，
 * 与通讯录页同一套）。第二步（头像 / 群名 / 成员）在 `CreateGroupProfileScreen`。
 * 两步流对齐 iOS `IMFriendPickerViewController` → `IMGroupCreateViewController`（2026-10-03 起，此前是单页）。
 *
 * **群成员上限由服务端 `/server-config` 下发，端上不得硬编码**——
 * 这条在三端约定里是明写的（要装更多人走超级群，不是调大这个数）。
 */
@Composable
fun CreateGroupScreen(
    friends: List<FriendEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    /** 「全选 / 取消全选」：传入新的整份选中集合（口径见 `GroupSelectAll`）。 */
    onSelectAll: (Set<String>) -> Unit,
    maxMembers: Int,
    /** 「下一步」：进建群资料页（头像/群名/成员）。一个好友都没选时禁用。 */
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 已选 + 我自己
    val atLimit = maxMembers > 0 && selected.size + 1 >= maxMembers
    var query by remember { mutableStateOf("") }

    val filtered = remember(friends, query) {
        // 走共用的 ListSearch（对齐 iOS `IMListSearchMatches`）：显示名（含备注）+ 用户名 + 内部 id（不显示，调试用，
        // 10 位随机数不会与名字撞）。此前这里自己手写 contains，少了 uid 一路
        if (query.isBlank()) friends
        else friends.filter { com.libeyond.imandroid.data.ListSearch.matches(query, listOf(it.displayName, it.username, it.userId)) }
    }
    val groups = remember(filtered) { ContactSection.group(filtered) { it.displayName } }
    val titles = remember(groups) { ContactSection.titlesOf(groups) }
    val groupStarts = remember(groups) { ContactSection.groupStartIndices(groups.map { it.items.size }, leadingItems = 0) }
    val listState = rememberLazyListState()
    val indexScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding().imePadding()) {
        // 第一步 = 选好友（对齐 iOS：独立的选人页，标题带已选数，右上「下一步」）
        IMTopBar(
            title = stringResource(R.string.friend_picker_title),
            subtitle = if (selected.isEmpty()) "" else stringResource(R.string.friend_picker_selected, selected.size),
            onLeft = onBack,
            actionText = stringResource(R.string.common_next),
            actionEnabled = selected.isNotEmpty(),
            onAction = onNext,
        )
        if (atLimit) {
            Text(
                stringResource(R.string.group_create_limit_reached, maxMembers),
                color = c.textTertiary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = d.space4, vertical = d.space2),
            )
        }

        IMTextField(
            query, { query = it }, stringResource(R.string.friend_picker_search_placeholder),
            enabled = true, modifier = Modifier.padding(horizontal = d.space4, vertical = d.space2),
        )

        val visibleIds = remember(filtered) { filtered.map { it.userId } }
        if (GroupSelectAll.isVisible(visibleIds)) {
            val allOn = GroupSelectAll.allSelected(selected, visibleIds)
            // 不随列表滚动（在 LazyColumn 之外）；点击区含文字左右各 8dp
            Row(Modifier.fillMaxWidth().height(32.dp).padding(end = d.space4 - 8.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.fillMaxHeight()
                        .clickable { onSelectAll(GroupSelectAll.next(selected, visibleIds, GroupSelectAll.limitOf(maxMembers))) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (allOn) R.string.common_deselect_all else R.string.common_select_all),
                        color = c.accent, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (filtered.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (query.isBlank()) {
                                    stringResource(R.string.contact_card_picker_empty)
                                } else {
                                    stringResource(R.string.friend_picker_no_match)
                                },
                                color = c.textTertiary,
                            )
                        }
                    }
                }
                groups.forEach { g ->
                    item(key = "h-" + g.key) {
                        Text(
                            g.key, color = c.textTertiary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = d.space4, top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(g.items, key = { it.userId }) { f ->
                        val checked = f.userId in selected
                        Row(
                            modifier = Modifier.fillMaxWidth().background(c.pageBackground)
                                // 已达上限时只允许**取消**勾选，不允许再勾
                                .clickable(enabled = checked || !atLimit) { onToggle(f.userId) }
                                .padding(horizontal = d.space4, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(22.dp).clip(CircleShape)
                                    .background(if (checked) c.accent else c.neutralControl),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) {
                                    Image(Lucide.Check, null, Modifier.size(13.dp),
                                        colorFilter = ColorFilter.tint(c.onAccent))
                                }
                            }
                            Spacer(Modifier.width(d.space3))
                            IMAvatar(f.displayName, seed = f.userId, avatarUrl = f.avatarUrl, size = 40.dp)
                            Spacer(Modifier.width(d.space3))
                            Column {
                                Text(f.displayName, color = c.textPrimary,
                                    style = MaterialTheme.typography.titleMedium)
                                if (f.handle.isNotEmpty()) {
                                    Text(f.handle, color = c.textSecondary,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            // 索引尺：搜索中不画（搜索结果本就是子集，跳字母意义不大，且组会随打字剧烈变动）
            if (query.isBlank()) {
                ContactIndexBar(
                    titles = titles,
                    onPick = { i -> groupStarts.getOrNull(i)?.let { indexScope.launch { listState.scrollToItem(it) } } },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}
