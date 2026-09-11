package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.PrivacySecurity
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 行高 60 / 头像 44（设计 §2.3，与 iOS `IMBlockedListViewController.rowHeight` 同）。 */
private val ROW_MIN_HEIGHT = 60.dp
private val ROW_AVATAR = 44.dp
private val EMPTY_ICON = 72.dp

/**
 * 已屏蔽的用户（对齐 iOS `IMBlockedListViewController`）：顶部说明 + 分组卡片 + **左滑「取消屏蔽」** + 空态三层。
 *
 * 纯展示，状态在 `PrivacySecurityHost`。与 iOS 相同的两处取舍：
 * ① 取消屏蔽**不二次确认**——可逆（再拉黑一次即可），且 iOS/Telegram 都是左滑直接生效；
 * ② 点行**不进资料页**（iOS 没实现 `didSelectRow`，设计稿里写了但没做）——要做三端一起加。
 */
@Composable
fun BlockedUsersScreen(
    /** null = 还没拉到。 */
    blocked: List<FriendEntry>?,
    /** 第一次就没拉到时的报错。已有列表时刷新失败不进这里——保留旧内容（iOS 同）。 */
    error: String,
    onUnblock: (FriendEntry) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 当前敞着的那一行：上提到列表层，滑开第二行时第一行自动收起（SwipeActionRow 的受控约定）
    var openedId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = PrivacySecurity.BLOCKED_TITLE, onLeft = onBack)

        if (blocked != null && blocked.isEmpty()) {
            // 空态时连顶部说明一起不显（iOS 把整个 tableView 藏了），免得和空态大标题抢焦点
            EmptyState()
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = PrivacySecurity.BLOCKED_HINT,
                color = c.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = d.space4 * 2, vertical = d.space3),
            )
            when {
                blocked == null && error.isNotEmpty() -> Text(
                    text = error,
                    color = c.danger,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(d.space4),
                )
                blocked == null -> Unit // 加载中：只留说明，不闪「加载中」（通常一瞬间就回来）
                else -> IMSettingsGroup {
                    blocked.forEachIndexed { i, f ->
                        if (i > 0) IMRowDivider(insetStart = d.space4 + ROW_AVATAR + d.space3)
                        val opened = openedId == f.userId
                        SwipeActionRow(
                            actions = listOf(
                                SwipeAction(PrivacySecurity.UNBLOCK, c.danger) { openedId = null; onUnblock(f) },
                            ),
                            opened = opened,
                            onOpenedChange = { open -> openedId = if (open) f.userId else null },
                        ) {
                            // 敞着的行点内容只收起；合着时点了不做事（同 iOS）
                            BlockedRow(f, onClick = if (opened) ({ openedId = null }) else null)
                        }
                    }
                }
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

@Composable
private fun BlockedRow(f: FriendEntry, onClick: (() -> Unit)?) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 不透明底色不能省：左滑时底下的红格要被内容层盖住
            .background(c.cardBackground)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = ROW_MIN_HEIGHT)
            .padding(horizontal = d.space4, vertical = d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(displayName = f.displayName, seed = f.userId, avatarUrl = f.avatarUrl, size = ROW_AVATAR)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = f.displayName,
                color = c.textPrimary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题是 @句柄，没有就整行不显——不回退内部 ID（iOS 同）
            if (f.handle.isNotEmpty()) {
                Text(f.handle, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

/** 空态三层：72dp 禁止图标 + 20sp 半粗大标题 + 小字副标题（设计 §2.1 / iOS `buildEmptyView`）。 */
@Composable
private fun EmptyState() {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(
        // 底部多垫一截：iOS 图标中心上移 40pt，整组视觉上略高于正中
        modifier = Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            imageVector = Lucide.Ban,
            contentDescription = null,
            modifier = Modifier.size(EMPTY_ICON),
            colorFilter = ColorFilter.tint(c.textTertiary),
        )
        Spacer(Modifier.height(d.space4))
        Text(
            text = PrivacySecurity.BLOCKED_EMPTY_TITLE,
            color = c.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = PrivacySecurity.BLOCKED_EMPTY_SUBTITLE,
            color = c.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
