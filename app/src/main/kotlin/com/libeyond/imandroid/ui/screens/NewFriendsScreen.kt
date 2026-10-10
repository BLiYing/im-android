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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 「新的朋友」（2026-09-05 三端统一的独立入口）。
 * 分「待我确认」/「已发出」/「已添加」三段（空段整段不出现；已添加 = 最近 30 天内同意的，见 NEW_FRIENDS_DESIGN）。
 * 已添加行**不放删除入口**（服务端只有「删好友关系」，没有「删申请记录」，见设计稿 §2）。
 */
@Composable
fun NewFriendsScreen(
    pending: List<FriendEntry>,
    requested: List<FriendEntry>,
    added: List<FriendEntry>,
    onOpen: (FriendEntry) -> Unit,
    onAccept: (FriendEntry) -> Unit,
    onReject: (FriendEntry) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.friend_requests_title), onLeft = onBack)

        if (pending.isEmpty() && requested.isEmpty() && added.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.friend_requests_empty), color = c.textTertiary)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (pending.isNotEmpty()) {
                item { Section(stringResource(R.string.friend_requests_incoming, pending.size)) }
                items(pending, key = { "p${it.userId}" }) { f ->
                    RequestRow(f, showActions = true, onAccept = { onAccept(f) }, onReject = { onReject(f) })
                }
            }
            if (requested.isNotEmpty()) {
                item { Section(stringResource(R.string.friend_requests_outgoing, requested.size)) }
                items(requested, key = { "r${it.userId}" }) { f ->
                    RequestRow(
                        f, showActions = false, onAccept = {}, onReject = {},
                        tag = stringResource(R.string.friend_requests_waiting),
                    )
                }
            }
            if (added.isNotEmpty()) {
                item { Section(stringResource(R.string.friend_requests_added, added.size)) }
                items(added, key = { "a${it.userId}" }) { f ->
                    RequestRow(
                        f, showActions = false, onAccept = {}, onReject = {},
                        tag = stringResource(R.string.friend_requests_added_tag),
                        subtitle = f.handle,
                        onClick = { onOpen(f) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text = text,
        color = IMTheme.colors.textTertiary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = IMTheme.dimens.space4, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun RequestRow(
    f: FriendEntry,
    showActions: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    /** 右侧纯文字标记（等待验证 / 已添加）；null = 不画。 */
    tag: String? = null,
    /** 非 null = 固定副标题（已添加行：@句柄，空则隐藏）；null = 沿用验证消息 / 句柄兜底。 */
    subtitle: String? = null,
    /** 非 null = 整行可点（已添加行进资料页）。 */
    onClick: (() -> Unit)? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().background(c.pageBackground)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(f.displayName, seed = f.userId, avatarUrl = f.avatarUrl, size = 40.dp)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(f.displayName, color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
            // 验证消息：给收件人看「他为什么加我」。**空了要隐藏整行**，不显示空白副标题。
            if (subtitle != null) {
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            } else if (f.hello.isNotBlank()) {
                Text(f.hello, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            } else if (f.handle.isNotEmpty()) {
                Text(f.handle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (tag != null) {
            Spacer(Modifier.width(d.space3))
            Text(tag, color = c.textTertiary, style = MaterialTheme.typography.bodyMedium)
        }
        if (showActions) {
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(c.accent)
                    .clickable { onAccept() }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.common_agree), color = c.onAccent, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.common_reject), color = c.textTertiary, modifier = Modifier.clickable { onReject() },
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
