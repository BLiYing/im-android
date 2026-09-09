package com.libeyond.imandroid.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 多选态的两件 UI：行左侧的勾选圈、底部的动作栏。
 *
 * 对端 iOS 是 `UITableView` 的系统编辑态（左侧系统圈）+ 一条 48pt 的液态玻璃选择栏；
 * Compose 没有编辑态这回事，所以自绘一个圈——**要对齐的是"哪些行有圈、勾了几条、能做什么"**，
 * 不是同一个控件（`SYMMETRY.md`）。判据全在 `data/ChatSelection.kt`。
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
 * 底部动作栏。**0 选中时各钮置灰禁用**（同 iOS：去掉「请先选择消息」那种吐司——
 * 按钮自己说清楚就不用再弹一句）。
 *
 * 本端只有「转发」「删除」两格：iOS 那侧还有「收藏」与「举报」，对应功能本端都还没做
 * （`CLIENT_PARITY` M4-4 与 AG 补两行），**不画只会弹「还没做」的死按钮**。
 */
@Composable
internal fun SelectionBar(
    count: Int,
    onForward: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = IMTheme.colors
    val enabled = count > 0
    Row(
        modifier = Modifier.fillMaxWidth().height(SELECTION_BAR_H).background(c.surface),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SelectionAction("转发", enabled, c.accent, onForward)
        SelectionAction("删除", enabled, c.danger, onDelete)
    }
}

@Composable
private fun SelectionAction(label: String, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(label, color = tint, fontWeight = FontWeight.Medium)
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
