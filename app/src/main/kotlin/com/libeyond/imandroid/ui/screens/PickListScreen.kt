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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** [PickListScreen] 的一行。把「群成员」「好友」抹平成同一种形状，免得为每种来源写一个选择页。 */
data class PickRow(val id: String, val name: String, val avatarUrl: String, val subtitle: String = "")

/**
 * 通用选人页：**单选立即回调，多选攒着按右上角确认**。
 *
 * 群这边有四处要选人——设管理员 / 撤管理员 / 转让群主 / 邀请入群——
 * 每处单写一个选择页，最后必然在「已选计数」「上限截断」「空态文案」上各写各的。
 * iOS 那边同样是 `IMFriendPickerViewController` / `IMGroupMemberSearchViewController` 复用。
 */
@Composable
internal fun PickListScreen(
    title: String,
    rows: List<PickRow>,
    /** 多选时的已选集合；单选传空。 */
    selected: Set<String> = emptySet(),
    multi: Boolean = false,
    /** 多选上限；`0` = 不限。超限时不再让勾。 */
    limit: Int = 0,
    emptyText: String = stringResource(R.string.common_no_selectable_people),
    onToggle: (String) -> Unit = {},
    onPick: (PickRow) -> Unit = {},
    onConfirm: () -> Unit = {},
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(
            title = title,
            subtitle = if (multi && selected.isNotEmpty()) {
                pluralStringResource(R.plurals.chat_select_selected, selected.size, selected.size)
            } else {
                ""
            },
            onLeft = onBack,
            actionText = if (multi) stringResource(R.string.common_confirm) else "",
            actionEnabled = selected.isNotEmpty(),
            onAction = if (multi) onConfirm else null,
        )
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text(emptyText, color = c.textTertiary, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { r ->
                val on = r.id in selected
                // 到上限后**未选中的行不再可点**——让它可点、点了没反应是最糟的一种
                val enabled = !multi || on || limit <= 0 || selected.size < limit
                Column {
                    Row(
                        Modifier.fillMaxWidth().background(c.surface)
                            .clickable(enabled = enabled) { if (multi) onToggle(r.id) else onPick(r) }
                            .padding(horizontal = d.space4, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (multi) {
                            Box(
                                Modifier.size(20.dp).clip(CircleShape)
                                    .background(if (on) c.accent else c.subtleFill),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Text("✓", color = c.onAccent, style = MaterialTheme.typography.bodySmall) }
                            Spacer(Modifier.width(d.space3))
                        }
                        IMAvatar(r.name, seed = r.id, avatarUrl = r.avatarUrl, size = 40.dp)
                        Spacer(Modifier.width(d.space3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.name,
                                color = if (enabled) c.textPrimary else c.textTertiary,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (r.subtitle.isNotEmpty()) {
                                Text(r.subtitle, color = c.textSecondary,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(start = 68.dp)
                        .height(0.5.dp).background(c.separator))
                }
            }
        }
    }
}
