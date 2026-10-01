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
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * 建群：头像（可选）+ 群名（30 rune 上限、自动预填）+ 搜索/索引选好友。
 *
 * 布局/交互对齐 iOS `IMGroupCreateViewController`——头像圈在最上头、群名带计数、
 * 好友列表按拼音 A–Z 分组带右侧索引尺（复用 `ContactSection`/`ContactIndexBar`，
 * 与通讯录页同一套，不是另起一份）。**未对齐处见 `CreateGroupHost` 头注释**：
 * 保留单页而不拆成 iOS 那样的「先选人再填资料」两步页。
 *
 * **群成员上限由服务端 `/server-config` 下发，端上不得硬编码**——
 * 这条在三端约定里是明写的（要装更多人走超级群，不是调大这个数）。
 */
@Composable
fun CreateGroupScreen(
    name: String,
    onNameChange: (String) -> Unit,
    friends: List<FriendEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    maxMembers: Int,
    busy: Boolean,
    error: String,
    avatarUrl: String,
    avatarUploading: Boolean,
    onPickAvatar: () -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 已选 + 我自己
    val atLimit = maxMembers > 0 && selected.size + 1 >= maxMembers
    var query by remember { mutableStateOf("") }

    val filtered = remember(friends, query) {
        if (query.isBlank()) friends
        else friends.filter { it.displayName.contains(query, ignoreCase = true) || it.handle.contains(query, ignoreCase = true) }
    }
    val groups = remember(filtered) { ContactSection.group(filtered) { it.displayName } }
    val titles = remember(groups) { ContactSection.titlesOf(groups) }
    val groupStarts = remember(groups) { ContactSection.groupStartIndices(groups.map { it.items.size }, leadingItems = 0) }
    val listState = rememberLazyListState()
    val indexScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding().imePadding()) {
        IMTopBar(
            title = stringResource(R.string.group_create_title),
            subtitle = if (selected.isEmpty()) {
                ""
            } else {
                stringResource(R.string.group_create_selected_count, selected.size, selected.size + 1)
            },
            onLeft = onBack,
        )

        Column(Modifier.padding(d.space4)) {
            // 头像圈：可选，点了选图 → 自动方裁上传；未选时按当前群名首字兜底，同列表里将来看到的样子
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape)
                        .background(c.neutralControl)
                        .clickable(enabled = !avatarUploading, onClick = onPickAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        avatarUploading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        avatarUrl.isNotBlank() -> AsyncImage(
                            model = avatarUrl, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                        name.isNotBlank() -> Text(
                            DisplayName.initials(name), color = c.onAccent,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        else -> Image(
                            Lucide.Camera, null, Modifier.size(28.dp),
                            colorFilter = ColorFilter.tint(c.textSecondary),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.group_create_set_avatar),
                    color = c.accent, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = d.space2, bottom = d.space2)
                        .clickable(enabled = !avatarUploading, onClick = onPickAvatar),
                )
            }

            IMTextField(name, onNameChange, stringResource(R.string.group_create_name_placeholder), enabled = !busy)
            Row(Modifier.fillMaxWidth().padding(top = d.space1), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text(
                    "${GroupNameDefault.runeLength(name)}/${GroupNameDefault.MAX_LENGTH}",
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
            }
            IMErrorText(error, Modifier.padding(top = d.space1))
            if (atLimit) {
                Text(
                    stringResource(R.string.group_create_limit_reached, maxMembers),
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = d.space2),
                )
            }
        }

        IMTextField(
            query, { query = it }, stringResource(R.string.friend_picker_search_placeholder),
            enabled = true, modifier = Modifier.padding(horizontal = d.space4, vertical = d.space2),
        )

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

        Box(Modifier.padding(d.space4)) {
            IMPrimaryButton(
                text = stringResource(R.string.group_create_title),
                onClick = onCreate,
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                loading = busy,
            )
        }
    }
}
