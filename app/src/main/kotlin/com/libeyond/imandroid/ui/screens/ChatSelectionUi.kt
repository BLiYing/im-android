package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Forward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareWarning
import com.composables.icons.lucide.Trash2
import com.libeyond.imandroid.data.SelectionActions
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 多选态的两件 UI：行左侧的勾选圈、底部的动作栏。
 *
 * 对端 iOS 是 `UITableView` 的系统编辑态（左侧系统圈）+ 一条 48pt 的液态玻璃选择栏；
 * Compose 没有编辑态这回事，所以自绘一个圈——**要对齐的是"哪些行有圈、勾了几条、能做什么"**，
 * 不是同一个控件（`SYMMETRY.md`）。判据全在 `data/ChatSelection.kt` 与 `data/SelectionActions.kt`。
 */

/** 行左侧的勾选圈。不可勾的行**整个不画**（同 iOS：`canEditRow=NO` 的行系统不画圈）。 */
@Composable
internal fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    Box(
        modifier = modifier.size(22.dp).clip(CircleShape)
            .background(if (selected) c.accent else Color.Transparent)
            .border(1.5.dp, if (selected) c.accent else c.textTertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = c.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 底部动作栏：**转发 / 举报 / 收藏 / 删除**四枚圆钮，顺序、两侧 16 边距、等分间距同 iOS
 * `IMChatViewController+Selection.m`（2026-09-10 用户报 #3：此前本端只有转发、删除两格）。
 *
 * - **0 选中时四枚全灰且不可点**（同 iOS：去掉「请先选择消息」那种吐司，按钮自己说清楚）。
 * - **举报另有三条判据**（见 [SelectionActions.reportableSender]）：不满足时**置灰但不隐藏**——
 *   隐藏会让栏里按钮数随勾选变化、左右跳；灰着仍接点击，由 [onReportBlocked] 说清为什么不能举报。
 */
@Composable
internal fun SelectionBar(
    selected: Map<Long, MessageEntity>,
    myUid: String,
    onForward: () -> Unit,
    onReport: () -> Unit,
    onReportBlocked: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = IMTheme.colors
    val any = selected.isNotEmpty()
    val canReport = remember(selected, myUid) {
        SelectionActions.reportableSender(selected.values.toList(), myUid) != null
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(SELECTION_BAR_H).background(c.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SelectionAction(Lucide.Forward, "转发", any, any, c.textPrimary, onForward)
        SelectionAction(
            Lucide.MessageSquareWarning, "举报", looksEnabled = canReport, clickable = any, tint = c.textPrimary,
            onClick = if (canReport) onReport else onReportBlocked,
        )
        SelectionAction(Lucide.Bookmark, "收藏", any, any, c.textPrimary, onFavorite)
        SelectionAction(Lucide.Trash2, "删除", any, any, c.danger, onDelete)
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    looksEnabled: Boolean,
    clickable: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val c = IMTheme.colors
    // 外层 44 是点击区，里面 36 的圆是 iOS 那枚玻璃钮的尺寸——36 做点击区在手机上偏小
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .clickable(enabled = clickable, onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(36.dp).alpha(if (looksEnabled) 1f else 0.35f)
                .clip(CircleShape).background(c.subtleFill),
            contentAlignment = Alignment.Center,
        ) {
            Image(icon, label, Modifier.size(20.dp), colorFilter = ColorFilter.tint(tint))
        }
    }
}

/** 选择栏高度。与 iOS `kIMSelectionBarH` 同为 48。 */
private val SELECTION_BAR_H = 48.dp

/** 勾选圈占的横向宽度（圈 22 + 右间距 8），行内容整体右移这么多。 */
internal val SELECTION_GUTTER = 30.dp

/** 勾选圈与行内容之间的间距。 */
@Composable
internal fun SelectionGutterSpacer() {
    Spacer(Modifier.width(8.dp))
}
