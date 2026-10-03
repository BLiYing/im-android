package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.GroupNameDefault
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 建群**第二步：群资料**（对齐 iOS `IMGroupCreateViewController`）：群头像圈 / 群名（30 rune 上限 + 计数）/
 * 成员条（44dp 头像 + 名字 + 右上 ✕，最多画 30 个、其余折成「+N」；右边固定一颗「＋ 添加」回第一步）。
 * 右上「创建」。✕ 移除成员，**不能删到 0**（到 1 个时再点提示「至少选择一位好友」）。
 */
@Composable
fun CreateGroupProfileScreen(
    name: String,
    onNameChange: (String) -> Unit,
    members: List<FriendEntry>,
    onRemove: (String) -> Unit,
    /** 回第一步继续挑人（第一步还在栈里、勾选保留，不是新开一个选人页）。 */
    onAddMore: () -> Unit,
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
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding().imePadding()) {
        IMTopBar(
            title = stringResource(R.string.group_create_title),
            onLeft = onBack,
            actionText = stringResource(R.string.common_create),
            // 头像还在传时不让创建：带不上头像的群之后还得再改（iOS 是等最多 5 秒，这里直接挡住更简单）
            actionEnabled = name.isNotBlank() && members.isNotEmpty() && !busy && !avatarUploading,
            onAction = onCreate,
        )
        Column(Modifier.padding(d.space4)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(c.neutralControl)
                        .clickable(enabled = !avatarUploading, onClick = onPickAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        avatarUploading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        avatarUrl.isNotBlank() -> AsyncImage(
                            model = avatarUrl, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                        name.isNotBlank() -> Text(DisplayName.initials(name), color = c.onAccent, style = MaterialTheme.typography.titleLarge)
                        else -> Image(Lucide.Camera, null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(c.textSecondary))
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (avatarUrl.isBlank()) R.string.group_avatar_add else R.string.group_avatar_change),
                    color = c.accent, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = d.space2).clickable(enabled = !avatarUploading, onClick = onPickAvatar),
                )
            }
            IMTextField(name, onNameChange, stringResource(R.string.group_create_name_placeholder), enabled = !busy)
            Row(Modifier.fillMaxWidth().padding(top = d.space1), horizontalArrangement = Arrangement.End) {
                Text(
                    "${GroupNameDefault.runeLength(name)}/${GroupNameDefault.MAX_LENGTH}",
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
            }
            IMErrorText(error, Modifier.padding(top = d.space1))
        }

        Text(
            stringResource(R.string.group_create_members_summary, members.size, members.size + 1),
            color = c.textTertiary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = d.space4, vertical = d.space2),
        )
        Row(Modifier.fillMaxWidth().background(c.pageBackground).padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
            val shown = members.take(MAX_CHIPS)
            LazyRow(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = d.space3)) {
                items(shown, key = { it.userId }) { f -> MemberChip(f, onRemove = { onRemove(f.userId) }) }
                if (members.size > MAX_CHIPS) {
                    item {
                        Box(Modifier.size(width = 54.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                            Text("+${members.size - MAX_CHIPS}", color = c.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
            // 固定在右缘、不随条滚走
            Column(
                Modifier.padding(horizontal = d.space3).clickable(onClick = onAddMore),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.neutralControl), contentAlignment = Alignment.Center) {
                    Text("＋", color = c.textPrimary, fontSize = 22.sp)
                }
                Text(stringResource(R.string.group_create_add_more).removePrefix("＋").trim(), color = c.accent, fontSize = 11.sp)
            }
        }
        if (maxMembers > 0) {
            // 群主占一个座位，所以可选成员上限 = 配置上限 − 1
            Text(
                stringResource(R.string.group_create_max_members, maxMembers - 1), color = c.textTertiary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = d.space4, vertical = d.space2),
            )
        }
    }
}

/** 一个成员：44dp 头像 + 右上 ✕ + 下面 11sp 名字（本机显示名，含备注）。 */
@Composable
private fun MemberChip(f: FriendEntry, onRemove: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.width(54.dp).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            IMAvatar(f.displayName, seed = f.userId, avatarUrl = f.avatarUrl, size = 44.dp)
            Box(
                Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(c.neutralControl)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Image(Lucide.X, stringResource(R.string.group_create_remove_member, f.displayName), Modifier.size(10.dp), colorFilter = ColorFilter.tint(c.textSecondary))
            }
        }
        Text(f.displayName, color = c.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val MAX_CHIPS = 30
