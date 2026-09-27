package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.Mention
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 输入栏**上方**的内联 @成员面板（M4-8，仅群聊）。
 *
 * 对端 iOS `IMMentionPickerViewController` 的内联形态：child VC 贴在输入区栈顶，
 * **不弹 sheet、不抢键盘**——弹窗会把键盘收掉，用户打完 `@` 还得再点一次输入框。
 * 本端同理：它只是聊天页底部栈里的一层，键盘照常开着，输入栏的 @后文字**实时驱动过滤**。
 *
 * 判据：
 * - 「@所有人」**只对群主/管理员**画（越权服务端回 300204，端上先不给入口）；它排在最前。
 * - 候选来自服务端 `?q=` 分页，不是本地成员表——超级群不下发成员表（见 rememberMentionComposer）。
 * - 面板高度封顶，超出可滚：群里同名前缀一大把时不能把整个聊天页顶没。
 */
@Composable
internal fun MentionPanel(
    members: List<GroupMember>,
    canMentionAll: Boolean,
    onPick: (displayName: String, uid: String?) -> Unit,
) {
    val c = IMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PANEL_MAX_HEIGHT)
            .background(c.surface),
    ) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        LazyColumn {
            if (canMentionAll) {
                item(key = "@all") {
                    MentionRow(
                        title = Mention.ALL_LABEL,
                        subtitle = stringResource(R.string.chat_mention_notify_all_subtitle),
                        seed = "",
                        avatarUrl = "",
                        badge = null,
                        onClick = { onPick(Mention.ALL_LABEL, null) },
                    )
                }
            }
            items(members, key = { it.userId }) { m ->
                MentionRow(
                    // 插进消息的是**群内公开名**，不是我给他起的备注——备注写进消息就发给全群了
                    title = m.displayName,
                    subtitle = m.handle,
                    seed = m.userId,
                    avatarUrl = m.avatarUrl,
                    badge = when {
                        m.isOwner -> stringResource(R.string.group_role_owner)
                        m.isAdmin -> stringResource(R.string.group_role_admin)
                        else -> null
                    },
                    onClick = { onPick(m.displayName, m.userId) },
                )
            }
        }
    }
}

@Composable
private fun MentionRow(
    title: String,
    subtitle: String,
    seed: String,
    avatarUrl: String,
    badge: String?,
    onClick: () -> Unit,
) {
    val c = IMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(displayName = title, seed = seed, avatarUrl = avatarUrl, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = c.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = badge,
                color = c.accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(c.accentSoft)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (subtitle.isNotEmpty()) {
            // 句柄靠右、可被名字挤掉：名字才是选人的依据，句柄只是同名时的区分线索
            Text(
                text = subtitle,
                color = c.textTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 面板最高多少。约 5 行——再高就把聊天页顶没了（同 iOS 内联卡的 preferredInlineHeight 口径）。 */
private val PANEL_MAX_HEIGHT = 240.dp
