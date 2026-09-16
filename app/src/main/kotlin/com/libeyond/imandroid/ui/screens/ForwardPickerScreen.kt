package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMCardSheet
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

// 行的尺寸照 iOS `IMForwardPickerCell`：34pt 头像、行高至少 50、勾选圈 22、间距 12
private val ROW_AVATAR = 34.dp
private val ROW_MIN_HEIGHT = 50.dp
private val CHECK_SIZE = 22.dp
private val ROW_GAP = 12.dp

/**
 * 转发目标选择页（M4-3）。**逐项对齐 iOS `IMForwardPickerViewController`**（2026-09-16 用户报：
 * 本端是一整屏平铺、不是 iOS 那种卡片，且不能搜索）：
 *
 * - **卡片式弹层**（[IMCardSheet]，iOS pageSheet）：可下拉关闭，标题「转发到」居中；
 * - **默认单选**：点一行弹「发送给「X」？」确认后发送；右上「多选」进多选，行首出勾选圈、
 *   右上变「发送(n)」，上限 [Forward.MAX_TARGETS]，超限吐司而不是静默丢弃；
 * - **搜索框**：按显示名 + 单聊对端 uid 过滤，只影响看得见哪些行、已选不丢（[Forward.pickable]）；
 * - 剔除系统通知单聊。
 *
 * [onConfirm] / [onCancel] 都在卡片**滑出屏幕之后**才回调。
 */
@Composable
fun ForwardPickerScreen(
    conversations: List<ConversationEntity>,
    onConfirm: (List<ConversationEntity>) -> Unit,
    onCancel: () -> Unit,
    onToast: (String) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var multi by remember { mutableStateOf(false) }
    // Set 的加减保持插入顺序：发送顺序 = 勾选顺序（iOS `_selected` 是有序数组）
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf<ConversationEntity?>(null) }
    val rows = remember(conversations, query) { Forward.pickable(conversations, query) }

    IMCardSheet(onDismissed = onCancel) { sheet ->
        IMTopBar(
            title = "转发到",
            leftLabel = "取消",
            onLeft = { sheet.dismiss(onCancel) },
            actionText = when {
                !multi -> "多选"
                selected.isEmpty() -> "发送"
                else -> "发送(${selected.size})"
            },
            actionEnabled = !multi || selected.isNotEmpty(),
            onAction = {
                if (!multi) {
                    multi = true
                } else {
                    val targets = Forward.targetsInOrder(selected.toList(), conversations)
                    sheet.dismiss { onConfirm(targets) }
                }
            },
        )
        IMSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索会话",
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
        )
        // 只在「搜了但没搜到」时说话：会话列表还没从库里读到时（调用方初值是空表）说「没有」再改口，就是闪一下空态
        if (rows.isEmpty() && query.isNotBlank()) {
            Text(
                "没有匹配的会话",
                color = c.textTertiary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.convId }) { conv ->
                ForwardRow(
                    conv = conv,
                    multi = multi,
                    checked = conv.convId in selected,
                    onClick = {
                        focus.clearFocus()
                        if (!multi) {
                            confirming = conv
                        } else {
                            val next = Forward.toggleTarget(selected, conv.convId)
                            if (next == null) onToast("最多选择 ${Forward.MAX_TARGETS} 个会话") else selected = next
                        }
                    },
                )
            }
        }
        confirming?.let { target ->
            IMConfirmDialog(
                title = "转发",
                message = "发送给「${Forward.titleOf(target)}」？",
                confirmText = "发送",
                destructive = false,
                onConfirm = { sheet.dismiss { onConfirm(listOf(target)) } },
                onDismiss = { confirming = null },
            )
        }
    }
}

@Composable
private fun ForwardRow(conv: ConversationEntity, multi: Boolean, checked: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val title = Forward.titleOf(conv)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .background(c.surface)
                .clickable(onClick = onClick)
                .padding(horizontal = d.space4, vertical = d.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multi) {
                Box(
                    modifier = Modifier
                        .size(CHECK_SIZE)
                        .clip(CircleShape)
                        .background(if (checked) c.accent else c.neutralControl),
                    contentAlignment = Alignment.Center,
                ) {
                    // 勾用图标不用「✓」字形：部分机型会拿彩色 emoji 字体画它（VideoPlayer 里记过同一个坑）
                    if (checked) Image(Lucide.Check, null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(c.onAccent))
                }
                Spacer(Modifier.width(ROW_GAP))
            }
            IMAvatar(
                displayName = title,
                seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
                avatarUrl = conv.avatarUrl,
                size = ROW_AVATAR,
            )
            Spacer(Modifier.width(ROW_GAP))
            Text(
                text = title,
                color = c.textPrimary,
                fontSize = 15.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!multi) {
                // 单选态行尾 ›（iOS disclosure indicator）；多选态由行首的圈承载选中，不再挂
                Image(Lucide.ChevronRight, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(c.textTertiary))
            }
        }
        // 分割线从名字起头（iOS 平铺表格的分割线缩进）
        val inset = d.space4 + ROW_AVATAR + ROW_GAP + if (multi) CHECK_SIZE + ROW_GAP else 0.dp
        Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(c.separator))
    }
}
