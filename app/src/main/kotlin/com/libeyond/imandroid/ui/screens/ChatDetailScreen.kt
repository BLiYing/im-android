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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 单聊详情页（M4.5-3）。
 *
 * 对齐 iOS `IMChatDetailViewController`：**单聊与群聊共用同一种"会话详情"**，
 * 而不是把单聊直接丢到「用户资料页」——那两件事不是一回事：
 * 用户资料是"这个人是谁"（备注、加好友、删好友），会话详情是"这段对话怎么设置"
 * （置顶、免打扰、这段对话里发过的媒体和文件）。本端此前单聊只有前者。
 *
 * **本页不做群聊**：群那边已经有 [GroupInfoScreen]（头部/公告/成员/群管理入口）。
 * 两边合并成一个组件要在里面塞满 `if (isGroup)`，比两份各自清楚的代码更难改——
 * iOS 合并是因为它还共用了 tabs 那一大套，本端还没有。
 */
@Composable
internal fun ChatDetailScreen(
    conv: ConversationEntity,
    /** 对端显示名（走备注优先的口径，由 Host 给）。 */
    title: String,
    /** `@句柄`；空则整行不显。 */
    handle: String,
    pinned: Boolean,
    muted: Boolean,
    onTogglePinned: (Boolean) -> Unit,
    onToggleMuted: (Boolean) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMedia: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = "聊天信息", onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // —— 头部：点进用户资料页 ——
            Row(
                Modifier.fillMaxWidth().background(c.pageBackground)
                    .clickable(onClick = onOpenProfile).padding(d.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IMAvatar(title, seed = conv.peerUid, avatarUrl = conv.avatarUrl, size = 56.dp)
                Spacer(Modifier.width(d.space3))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    // 标识行为空时**整行隐藏**——不显示「用户名：未设置」，更不回退内部 ID
                    if (handle.isNotEmpty()) {
                        Text(handle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
                Text("›", color = c.textTertiary)
            }

            Spacer(Modifier.height(d.cardGap))
            Card {
                ChevronRow("聊天媒体", onClick = onOpenMedia)
            }

            Spacer(Modifier.height(d.cardGap))
            Card {
                // 两项都走 PUT /conversations/{id}/settings，而那是**整体替换**三项，
                // 所以改一项也要把另外两项原样带回（Host 里做，见 ChatDetailHost）。
                SwitchRow("置顶聊天", pinned, onTogglePinned)
                Divider()
                SwitchRow("消息免打扰", muted, onToggleMuted)
            }

            Text(
                "「查找聊天记录」「清空聊天记录」还没做。",
                color = c.textTertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = d.space4, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4)
            .clip(RoundedCornerShape(IMTheme.dimens.radiusCard))
            .background(IMTheme.colors.cardBackground),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        Modifier.fillMaxWidth().padding(start = IMTheme.dimens.space4)
            .height(0.5.dp).background(IMTheme.colors.separator),
    )
}

@Composable
private fun ChevronRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = IMTheme.dimens.space4, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = IMTheme.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", color = IMTheme.colors.textTertiary)
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = IMTheme.dimens.space4, end = IMTheme.dimens.space3, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = IMTheme.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onToggle)
    }
}
