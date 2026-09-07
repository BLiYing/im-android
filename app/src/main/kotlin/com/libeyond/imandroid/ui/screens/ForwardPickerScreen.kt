package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 转发目标选择页（M4-3）。
 *
 * 与 iOS/Web 同口径：**可多选**，上限 [Forward.MAX_TARGETS]（9 个会话）——
 * 超限吐司而不是静默丢弃，否则用户以为选上了、发完才发现少了几个。
 *
 * 刻意**不做搜索框**：本端会话列表还没接列表搜索（`IMListSearch` / `listSearch.ts` 那一套），
 * 单独在这里塞一个会变成第四份各写各的实现。等搜索收敛时一起接。
 */
@Composable
fun ForwardPickerScreen(
    conversations: List<ConversationEntity>,
    /** 转发的消息条数，用于标题。 */
    count: Int,
    onConfirm: (List<ConversationEntity>) -> Unit,
    onCancel: () -> Unit,
    onToast: (String) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var selected by remember { mutableStateOf(setOf<String>()) }

    Column(
        // systemBarsPadding 不能漏：本页是**盖在聊天页之上的整屏**，
        // 不加的话标题栏会画到状态栏底下（实测第一版就是这样）。
        modifier = Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding(),
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
                "取消",
                color = c.accent,
                modifier = Modifier.clickable { onCancel() },
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.width(d.space4))
            Text(
                text = if (count > 1) "转发 $count 条消息" else "转发到",
                color = c.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (selected.isEmpty()) "发送" else "发送(${selected.size})",
                color = if (selected.isEmpty()) c.textTertiary else c.accent,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clickable(enabled = selected.isNotEmpty()) {
                    onConfirm(conversations.filter { it.convId in selected })
                },
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(conversations.size, key = { conversations[it].convId }) { i ->
                val conv = conversations[i]
                val title = conv.title.ifBlank { conv.convId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = d.convRowHeight)
                        .background(c.pageBackground)
                        .clickable {
                            val next = Forward.toggleTarget(selected, conv.convId)
                            if (next == null) {
                                onToast("最多选择 ${Forward.MAX_TARGETS} 个会话")
                            } else selected = next
                        }
                        .padding(horizontal = d.space4, vertical = d.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 选中标记放最左（与"发送"按钮的计数对得上，扫一眼就知道选了哪些）
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (conv.convId in selected) c.accent else c.neutralControl),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (conv.convId in selected) {
                            Text("✓", color = c.onAccent, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.width(d.space3))
                    IMAvatar(
                        displayName = title,
                        seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
                        avatarUrl = conv.avatarUrl,
                        size = d.convAvatar,
                    )
                    Spacer(Modifier.width(d.space3))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = title,
                            color = c.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
        }
    }
}
