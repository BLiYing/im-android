package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 会话列表（CLIENT_PARITY M1「会话列表」行）。
 *
 * 纯展示：数据与动作全经参数注入，不持业务状态（CODING_STYLE §7②）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    conversations: List<ConversationEntity>,
    onOpen: (ConversationEntity) -> Unit,
    /** 长按一行，带上它在窗口坐标系里的矩形——菜单要贴着这一行弹（对齐 iOS UIContextMenu）。 */
    onLongPress: (ConversationEntity, androidx.compose.ui.geometry.Rect) -> Unit,
    onSettings: () -> Unit,
    connected: Boolean,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.groupedBackground)
            .systemBarsPadding(),
    ) {
        // —— 标题栏 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = d.space4, vertical = d.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "消息",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
            )
            // 连接状态：只在**未连接**时占位显示，连上了就不打扰用户
            if (!connected) {
                Spacer(Modifier.width(d.space2))
                Text(
                    text = "连接中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textTertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "我",
                color = c.accent,
                modifier = Modifier.clickable { onSettings() },
            )
        }

        if (conversations.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(conversations, key = { it.convId }) { conv ->
                    ConversationRow(conv, onClick = { onOpen(conv) }, onLongClick = { r -> onLongPress(conv, r) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: ConversationEntity,
    onClick: () -> Unit,
    onLongClick: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    var rect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val title = conv.title.ifBlank { conv.convId }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高 76 = 12 + 52 + 12（UI_SPEC §2，iOS 写死 rowHeight 76）。
            // 用 heightIn(min) 不用 height：长昵称换行时允许长高，不裁内容。
            .heightIn(min = d.convRowHeight)
            .background(c.pageBackground)
            .onGloballyPositioned { rect = it.boundsInWindow() }
            .combinedClickable(onClick = onClick, onLongClick = { onLongClick(rect) })
            .padding(horizontal = d.space4, vertical = d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(
            displayName = title,
            // 种子用 uid 不用显示名——改昵称不该换颜色
            seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
            avatarUrl = conv.avatarUrl,
            size = d.convAvatar,
        )
        Spacer(Modifier.width(d.space3))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = c.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conv.pinnedAt > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text("📌", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 群里有人 @我：红字前缀，且**穿透免打扰**
                if (conv.mentionUnread) {
                    Text(
                        text = "[有人@我] ",
                        color = c.danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = conv.lastContent,
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(d.space2))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = TimeFormat.conversationTime(conv.lastTimestamp),
                color = c.textTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
            UnreadBadge(conv)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(start = d.convSeparatorInset)
            .background(c.separator),
    )
}

/**
 * 未读徽标。
 * - 免打扰会话显示**灰点**不显示数字（弱提示），但 **@我 仍要红底数字**——穿透免打扰。
 * - 手动标未读显示小红点、不计数。
 */
@Composable
private fun UnreadBadge(conv: ConversationEntity) {
    val c = IMTheme.colors
    when {
        conv.unread > 0 -> {
            val strongAlert = !conv.muted || conv.mentionUnread
            val d = IMTheme.dimens
            Box(
                // 高 20、最小宽 20（UI_SPEC §2，与 iOS _badge.heightAnchor 同值）：
                // 个位数是正圆，两位数才拉成胶囊。
                modifier = Modifier
                    .height(d.unreadBadgeHeight)
                    .widthIn(min = d.unreadBadgeHeight)
                    .clip(CircleShape)
                    .background(if (strongAlert) c.unreadBadge else c.textTertiary)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (conv.unread > 99) "99+" else conv.unread.toString(),
                    color = c.onAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        conv.markedUnread -> Box(
            modifier = Modifier.size(10.dp).clip(CircleShape).background(c.unreadBadge),
        )
        conv.muted -> Text("🔕", fontSize = 11.sp)
        else -> Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun EmptyState() {
    val c = IMTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有会话", color = c.textSecondary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "在另一个端给这个账号发条消息试试",
            color = c.textTertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
