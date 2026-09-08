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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.Presence
import com.libeyond.imandroid.data.MemberProfile
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMSecondaryButton
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 单聊资料页（M4.5）。
 *
 * **进页即按本地已知关系定型**——不能先闪一遍好友界面再变「加好友」。
 * 关系由调用方给定（来自本地好友表），页面只按它渲染。
 *
 * **非好友只显「加好友」一个入口，连「更多」也不显**（2026-08-30 三端收口）：
 * 给陌生人一个「更多」菜单，里面能干的事其实都需要好友关系，点开全是灰的。
 */
@Composable
fun UserProfileScreen(
    card: UserCard,
    /** accepted / pending / requested / blocked / 空=陌生人 */
    relation: String,
    onSendMessage: () -> Unit,
    onAddFriend: () -> Unit,
    onSetRemark: () -> Unit,
    onRemoveFriend: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val isFriend = relation == "accepted"

    Column(
        Modifier.fillMaxSize().background(c.groupedBackground)
            .systemBarsPadding().verticalScroll(rememberScrollState()),
    ) {
        // 标题留空：这一页的"标题"就是下面那张大头像 + 名字（iOS 同）
        IMTopBar(title = "", onLeft = onBack, showDivider = false)

        // —— 头部 ——
        Row(
            modifier = Modifier.fillMaxWidth().background(c.pageBackground).padding(d.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IMAvatar(card.displayName, seed = card.userId, avatarUrl = card.avatarUrl, size = 64.dp)
            Spacer(Modifier.width(d.space3))
            Column {
                Text(
                    card.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.textPrimary,
                )
                // 副标题=在线态。**不重复显示下方已列出的 @句柄**（2026-08-30 收口）
                val presenceLabel = Presence.label(
                    Presence.display(card.presence, card.onlineUntil, card.lastSeen, System.currentTimeMillis()),
                    System.currentTimeMillis(),
                )
                if (presenceLabel.isNotEmpty()) {
                    Text(
                        presenceLabel,
                        color = if (presenceLabel == "在线") c.online else c.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(d.cardGap))

        // —— 信息行 ——
        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.space4)
                .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground),
        ) {
            // 标识行为空时**整行隐藏**——不显示「用户名：未设置」，更不回退内部 ID
            if (card.handle.isNotEmpty()) InfoRow("用户名", card.handle)
            if (isFriend) {
                Divider()
                InfoRow("备注名", card.remark.ifBlank { "未设置" }, onClick = onSetRemark)
            }
            if (card.tags.isNotEmpty()) {
                Divider()
                InfoRow("标签", card.tags.joinToString("、"))
            }
        }

        Spacer(Modifier.height(24.dp))

        Column(Modifier.padding(horizontal = d.space4)) {
            when {
                isFriend -> {
                    IMPrimaryButton("发消息", onSendMessage)
                    Spacer(Modifier.height(d.cardGap))
                    IMSecondaryButton("删除好友", onRemoveFriend)
                }
                relation == "requested" -> Text(
                    "好友申请已发出，等待对方确认",
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                relation == "pending" -> IMPrimaryButton("同意添加", onAddFriend)
                relation == "blocked" -> Text("已拉黑", color = c.textTertiary)
                // 看自己（从群成员列表点到自己头上）：不给任何关系操作。
                // 给自己显示一个「加好友」按钮是本页最容易漏掉的一种荒谬状态。
                relation == MemberProfile.RELATION_SELF -> Unit
                // 陌生人：只给这一个入口
                else -> IMPrimaryButton("加好友", onAddFriend)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = d.space4, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textSecondary, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(value, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Text("›", color = c.textTertiary)
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier.fillMaxWidth().height(0.5.dp)
            .padding(start = IMTheme.dimens.space4)
            .background(IMTheme.colors.separator),
    )
}
