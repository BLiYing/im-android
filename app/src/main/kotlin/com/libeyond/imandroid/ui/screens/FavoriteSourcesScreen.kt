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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.SourceGroup
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 收藏页的「以聊天模式查看」：按来源会话分组的列表（iOS `IMFavoriteSourceCell`：头像 + 名字 + 最近一条的预览 + 右侧条数）。
 * 点一行下钻到只含该来源的收藏页（宿主处理）；本页没有分类签，搜索只匹配来源名。
 */
@Composable
internal fun FavoriteSourcesScreen(
    groups: List<SourceGroup>,
    nameOf: (String) -> String,
    avatarUrlOf: (String) -> String,
    previewOf: (SourceGroup) -> String,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    chatMode: Boolean,
    onMode: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(
            title = stringResource(R.string.common_saved_messages),
            subtitle = stringResource(R.string.favorites_mode_chats),
            onLeft = onBack,
            right = { FavoritesModeMenu(chatMode, onMode) },
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item(key = "search") {
                IMSearchField(
                    value = query, onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.favorites_source_search_placeholder),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4).padding(top = 8.dp, bottom = 8.dp),
                )
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        stringResource(if (query.isBlank()) R.string.fav_empty else R.string.favorites_empty_no_results),
                        color = c.textSecondary, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            items(groups, key = { it.key }) { g ->
                val name = nameOf(g.key)
                Row(
                    Modifier.fillMaxWidth().height(68.dp).background(c.surface).clickable { onOpen(g.key) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IMAvatar(displayName = name, seed = g.key, size = 44.dp, avatarUrl = avatarUrlOf(g.key))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = c.textPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(previewOf(g), color = c.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(g.items.size.toString(), color = c.textSecondary, fontSize = 14.sp)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
    }
}

/** 右上角 ⋯：以消息模式 / 以聊天模式查看（当前项前缀 ✓，iOS 同）。 */
@Composable
internal fun FavoritesModeMenu(chatMode: Boolean, onMode: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(
            Lucide.Ellipsis, stringResource(R.string.favorites_mode_accessibility_label),
            tint = IMTheme.colors.textPrimary,
            modifier = Modifier.clickable { open = true }.padding(8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text((if (!chatMode) "✓ " else "") + stringResource(R.string.favorites_mode_messages)) },
                onClick = { open = false; onMode(false) },
            )
            DropdownMenuItem(
                text = { Text((if (chatMode) "✓ " else "") + stringResource(R.string.favorites_mode_chats)) },
                onClick = { open = false; onMode(true) },
            )
        }
    }
}
