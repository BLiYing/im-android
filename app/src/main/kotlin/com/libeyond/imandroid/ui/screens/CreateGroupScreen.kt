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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 建群：填群名 + 勾好友。
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
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 已选 + 我自己
    val atLimit = maxMembers > 0 && selected.size + 1 >= maxMembers

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(c.surface)
                .padding(horizontal = d.space3, vertical = d.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                Lucide.ArrowLeft, "返回", Modifier.size(24.dp).clickable { onBack() },
                colorFilter = ColorFilter.tint(c.accent),
            )
            Spacer(Modifier.width(d.space3))
            Text("发起群聊", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            Spacer(Modifier.weight(1f))
            Text("已选 ${selected.size}", color = c.textSecondary,
                style = MaterialTheme.typography.bodyMedium)
        }

        Column(Modifier.padding(d.space4)) {
            IMTextField(name, onNameChange, "群名称", enabled = !busy)
            IMErrorText(error, Modifier.padding(top = d.space2))
            if (atLimit) {
                Text(
                    "已达本群成员上限（$maxMembers 人）。要装更多人请走大群。",
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = d.space2),
                )
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(friends, key = { it.userId }) { f ->
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

        Box(Modifier.padding(d.space4)) {
            IMPrimaryButton(
                text = "创建群聊",
                onClick = onCreate,
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                loading = busy,
            )
        }
    }
}
