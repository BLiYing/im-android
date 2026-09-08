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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 找人（M2.5-3）。
 *
 * **服务端按 username / phone 精确匹配，防枚举**——不是模糊搜索，
 * 界面文案要如实说清楚，否则用户输个昵称搜不到会以为坏了。
 */
@Composable
fun UserSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<UserCard>,
    /** uid → 与我的关系（accepted/pending/requested/blocked/空=陌生人）。 */
    relations: Map<String, String>,
    searching: Boolean,
    searched: Boolean,
    error: String,
    onAdd: (UserCard) -> Unit,
    onOpenChat: (UserCard) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(
        modifier = Modifier.fillMaxSize().background(c.groupedBackground)
            .systemBarsPadding().imePadding(),
    ) {
        IMTopBar(title = "找人", onLeft = onBack)

        Column(Modifier.padding(d.space4)) {
            IMTextField(query, onQueryChange, "用户名或手机号")
            Spacer(Modifier.size(d.space2))
            Text(
                "按用户名或手机号**精确**匹配（防止批量枚举），昵称搜不到",
                color = c.textTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(d.space2))
            com.libeyond.imandroid.ui.components.IMPrimaryButton(
                text = "搜索", onClick = onSearch,
                enabled = query.isNotBlank(), loading = searching,
            )
            IMErrorText(error, Modifier.padding(top = d.space2))
        }

        if (searched && results.isEmpty() && !searching) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("没找到这个用户", color = c.textTertiary)
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.userId }) { u ->
                ResultRow(
                    user = u,
                    relation = relations[u.userId].orEmpty(),
                    onAdd = { onAdd(u) },
                    onOpenChat = { onOpenChat(u) },
                )
            }
        }
    }
}

@Composable
private fun ResultRow(user: UserCard, relation: String, onAdd: () -> Unit, onOpenChat: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().background(c.pageBackground)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(user.displayName, seed = user.userId, avatarUrl = user.avatarUrl, size = 40.dp)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
            if (user.handle.isNotEmpty()) {
                Text(user.handle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 按关系给不同动作，与 iOS/Web 同一矩阵
        when (relation) {
            FriendEntry.ACCEPTED -> ActionChip("发消息", onOpenChat)
            FriendEntry.REQUESTED -> Text("已申请", color = c.textTertiary)
            FriendEntry.PENDING -> ActionChip("同意", onAdd)
            FriendEntry.BLOCKED -> Text("已拉黑", color = c.textTertiary)
            else -> ActionChip("加好友", onAdd)
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    Box(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(c.accentSoft)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, color = c.accent, style = MaterialTheme.typography.bodyMedium) }
}
