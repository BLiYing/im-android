package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.IdCard
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.UserMinus
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Video
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailActions
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 详情页头部的**操作排**（对齐 iOS `IMChatDetailViewController+Header.m` 的 `pillsView`）。
 *
 * 每个按钮是「图标在上、11sp 文字在下」的圆角块，整排等宽铺开——与 iOS
 * `imagePlacement = NSDirectionalRectEdgeTop / imagePadding 4 / 标题 11pt` 同构。
 *
 * **「更多」用 Material 的 [DropdownMenu] 而不是重画一个 iOS 式 popover**：
 * 这是刻意的平台差异（差异档 §4）。它锚在按钮上、点外面关闭、返回键关闭，
 * 语义与 `IMPopoverCard` 一致，而自绘一个只会多出一套要维护的定位代码。
 *
 * @param moreItems 「更多」里显示哪几项——由 [DetailActions.moreFor] 算，不要在这里判身份。
 */
@Composable
fun DetailActionBar(
    actions: List<DetailAction>,
    moreItems: List<DetailMoreAction>,
    onAction: (DetailAction) -> Unit,
    onMore: (DetailMoreAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val d = IMTheme.dimens
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = d.space4),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { a ->
            Box(Modifier.weight(1f)) {
                ActionPill(
                    icon = iconFor(a),
                    label = DetailActions.label(a),
                    onClick = { if (a == DetailAction.More) menuOpen = true else onAction(a) },
                )
                if (a == DetailAction.More) {
                    // 菜单锚在「更多」这一格上（同 iOS 的 presentFromAnchor:）
                    MoreMenu(
                        open = menuOpen,
                        items = moreItems,
                        onDismiss = { menuOpen = false },
                        onPick = { menuOpen = false; onMore(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.cardBackground)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(c.accent))
        Spacer(Modifier.height(4.dp))
        Text(label, color = c.accent, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MoreMenu(
    open: Boolean,
    items: List<DetailMoreAction>,
    onDismiss: () -> Unit,
    onPick: (DetailMoreAction) -> Unit,
) {
    val c = IMTheme.colors
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        items.forEach { m ->
            val fg = if (DetailActions.destructive(m)) c.danger else c.textPrimary
            DropdownMenuItem(
                text = { Text(DetailActions.label(m), color = fg) },
                leadingIcon = {
                    Image(
                        moreIconFor(m), null, Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(fg),
                    )
                },
                onClick = { onPick(m) },
            )
        }
    }
}

/** 图标是**语义等价物**不是同一张图（SF Symbol 与 Lucide 各画各的，见差异档 §4）。 */
private fun iconFor(a: DetailAction): ImageVector = when (a) {
    DetailAction.AddFriend -> Lucide.UserPlus       // person.badge.plus
    DetailAction.Message -> Lucide.MessageCircle    // bubble.right.fill
    DetailAction.Call -> Lucide.Phone               // phone.fill
    DetailAction.Video -> Lucide.Video              // video.fill
    DetailAction.Search -> Lucide.Search            // magnifyingglass
    DetailAction.More -> Lucide.Ellipsis            // ellipsis
}

private fun moreIconFor(m: DetailMoreAction): ImageVector = when (m) {
    DetailMoreAction.ShareContact -> Lucide.IdCard
    DetailMoreAction.Block, DetailMoreAction.Unblock -> Lucide.Hand
    DetailMoreAction.Report -> Lucide.Flag
    DetailMoreAction.ClearHistory -> Lucide.Trash2
    DetailMoreAction.RemoveFriend -> Lucide.UserMinus
    DetailMoreAction.LeaveGroup -> Lucide.LogOut
    DetailMoreAction.DissolveGroup -> Lucide.Trash2
}
