package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 会话列表编辑模式的底部选择栏（设计 `CONV_LIST_EDIT_MODE_DESIGN.md` §3）：左「全选 / 取消全选」，右「已读」「删除」。
 *
 * 样式照聊天多选栏 [SelectionBar]：`surface` 底、高 48dp、左右 16dp、`SpaceBetween`、不可用 35% 透明；
 * 区别只有一处——这里动作少且含义必须一眼看清，所以是**文字钮**（36dp 高胶囊、`subtleFill` 底）而不是图标圆钮。
 * 刻意不做液态玻璃（UI_SPEC §6.4）。独立占一条，不与列表重叠：由 [com.libeyond.imandroid.ui.TabRoot] 的底栏插槽承载。
 */
@Composable
internal fun ConversationEditBar(
    allSelected: Boolean,
    canSelectAll: Boolean,
    canRead: Boolean,
    canDelete: Boolean,
    onSelectAll: () -> Unit,
    onRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()
            .height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(if (allSelected) R.string.common_deselect_all else R.string.common_select_all),
            color = c.accent, fontSize = 16.sp,
            modifier = Modifier.alpha(if (canSelectAll) 1f else DISABLED_ALPHA)
                .clickable(enabled = canSelectAll, onClick = onSelectAll),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            EditBarPill(stringResource(R.string.conv_edit_mark_read), c.textPrimary, canRead, onRead)
            EditBarPill(stringResource(R.string.common_delete), c.danger, canDelete, onDelete)
        }
    }
}

private const val DISABLED_ALPHA = 0.35f

@Composable
private fun EditBarPill(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(18.dp)).background(IMTheme.colors.subtleFill)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .height(36.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = color, fontSize = 15.sp)
    }
}
