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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.JoinRequest
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 待审入群申请（G3，群主/管理员）。
 *
 * **分「待处理 / 已处理」两段**，与 im-web `JoinRequestsModal` 同结构：
 * 只显待处理的话，审批完那一下整个列表会空掉，看着像操作没生效
 * ——而这一步恰好是不可撤销的，用户最需要看到「刚才那下确实生效了」。
 *
 * 拉的是**全量**（`status=""`），已处理的那段就是从同一批里筛出来的。
 */
@Composable
internal fun JoinRequestsScreen(
    requests: List<JoinRequest>,
    loading: Boolean,
    /** 正在提交的那一条（按 uid）；期间两个按钮都不可点，避免连点发两次。 */
    busyUid: String,
    onDecide: (uid: String, approve: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var showDone by remember { mutableStateOf(false) }
    val pending = requests.filter { it.isPending }
    val done = requests.filterNot { it.isPending }
    val shown = if (showDone) done else pending

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.qr_join_req_title), onLeft = onBack)

        // 页签条与资料页/收藏页同一份（SegTabBar：居中药丸条），不再各画各的
        SegTabBar(
            titles = listOf(
                if (pending.isNotEmpty()) {
                    stringResource(R.string.qr_join_req_tab_pending_count, pending.size)
                } else {
                    stringResource(R.string.qr_join_req_tab_pending)
                },
                stringResource(R.string.qr_join_req_tab_done),
            ),
            selected = if (showDone) 1 else 0,
            onSelect = { showDone = it == 1 },
        )

        when {
            loading -> Empty(stringResource(R.string.common_loading))
            shown.isEmpty() -> Empty(
                if (showDone) stringResource(R.string.qr_join_req_empty_done) else stringResource(R.string.qr_join_req_empty_pending),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.userId }) { r ->
                    RequestRow(r, busy = busyUid == r.userId, onDecide = onDecide)
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = IMTheme.colors.textTertiary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RequestRow(r: JoinRequest, busy: Boolean, onDecide: (String, Boolean) -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = d.space4, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IMAvatar(r.displayName, seed = r.userId, avatarUrl = r.avatarUrl, size = 40.dp)
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                Text(r.displayName, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
                // 验证消息为空时**整行不显**，不写「未填写」——那是噪音
                if (r.hello.isNotBlank()) {
                    Text(r.hello, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (r.isPending) {
                Spacer(Modifier.width(d.space3))
                // 「同意」是主操作用实心，「拒绝」次级——两个都做成一样重的按钮
                // 容易让人在列表里连点错（这一步不可撤销）
                Pill(stringResource(R.string.common_agree), primary = true, enabled = !busy) { onDecide(r.userId, true) }
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.common_reject), primary = false, enabled = !busy) { onDecide(r.userId, false) }
            } else {
                Text(
                    if (r.status == "approved") stringResource(R.string.qr_join_req_approved) else stringResource(R.string.qr_join_req_rejected),
                    color = if (r.status == "approved") c.textSecondary else c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}

@Composable
private fun Pill(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (primary) c.accent else c.subtleFill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (primary) c.onAccent else c.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
