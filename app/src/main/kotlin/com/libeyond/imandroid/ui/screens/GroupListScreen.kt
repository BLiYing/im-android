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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 我加入的群聊列表（通讯录 →「群聊」入口）。对齐 iOS `IMGroupListViewController`：
 * 标题「群聊」、右上角 `+` 建群、空态那句话直接指向 `+`。
 *
 * **它不是会话列表的子集视图**：没聊过的群在会话列表里可能一行都没有，
 * 但仍然在这里。数据来自 `GET /groups`（`client.groups.myGroups()`），不读本地会话表。
 */
@Composable
internal fun GroupListScreen(
    groups: List<GroupInfo>,
    loading: Boolean,
    /** 本机对某人的显示名（备注优先）。取不到返回 null → 回退群主公开昵称。 */
    myUid: String,
    localNameOf: (String) -> String?,
    onOpen: (GroupInfo) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(
            title = "群聊",
            onLeft = onBack,
            right = {
                Image(
                    Lucide.Plus, "创建群聊",
                    Modifier.size(22.dp).clickable(onClick = onCreate),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            },
        )

        when {
            groups.isEmpty() && loading -> Hint("加载中…")
            groups.isEmpty() -> Hint("还没有加入群聊，点右上角 + 创建")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.convId }) { g ->
                    Row(
                        Modifier.fillMaxWidth().background(c.pageBackground)
                            .clickable { onOpen(g) }
                            .padding(horizontal = d.space4, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IMAvatar(g.name, seed = g.convId, avatarUrl = g.avatarUrl, size = 40.dp)
                        Spacer(Modifier.width(d.space3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                g.name.ifBlank { "未命名群聊" }, color = c.textPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            // 副标题是**群主**，不是人数——`GET /groups` 根本不下发 member_count
                            // （见 `group.Summary`），照着详情页写「N 人」的结果是恒显「0 人」。
                            // 口径逐字对齐 iOS `configureWithGroup:mine:`。
                            Text(
                                if (g.owner == myUid) {
                                    "我是群主"
                                } else {
                                    "群主 " + (
                                        localNameOf(g.owner)
                                            ?: g.ownerNickname.ifBlank {
                                                if (g.ownerUsername.isBlank()) "未命名用户" else "@" + g.ownerUsername
                                            }
                                        )
                                },
                                color = c.textSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = 68.dp)
                        .background(c.separator))
                }
            }
        }
    }
}
