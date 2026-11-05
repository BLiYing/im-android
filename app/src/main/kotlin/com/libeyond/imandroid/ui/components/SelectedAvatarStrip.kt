package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.ui.theme.IMTheme

/** [SelectedAvatarStrip] 的一项：稳定身份 [id]（列表 key）、显示名、头像种子与地址。 */
data class StripItem(val id: String, val name: String, val seed: String, val avatarUrl: String)

/**
 * 横向滚动的「已选」头像条：44dp 头像 + 右上 ✕ + 下面 11sp 名字，按传入顺序排列。
 *
 * 原是建群资料页（`CreateGroupProfileScreen`）的成员条，抽出来给分享选择页底部已选栏复用
 * （SHARE_PICKER_UX_SKETCH ④：两处同一种条，别各画一份）。超过 [maxChips] 的折成「+N」。
 *
 * @param removeDescription ✕ 的无障碍描述（参数是显示名）。
 * @param followTail 条目变多时自动滚到末尾（分享页：刚勾的人要看得见；建群页不需要）。
 * @param tapToRemove 点头像本身也算移除（分享页草图 ④「点头像就是取消勾选」）；建群页只认 ✕。
 */
@Composable
fun SelectedAvatarStrip(
    items: List<StripItem>,
    onRemove: (String) -> Unit,
    removeDescription: (String) -> String,
    modifier: Modifier = Modifier,
    maxChips: Int = Int.MAX_VALUE,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    followTail: Boolean = false,
    tapToRemove: Boolean = false,
) {
    val c = IMTheme.colors
    val state = rememberLazyListState()
    val shown = remember(items, maxChips) { items.take(maxChips) }
    if (followTail) {
        LaunchedEffect(shown.size) { if (shown.isNotEmpty()) state.animateScrollToItem(shown.lastIndex) }
    }
    LazyRow(modifier, state = state, contentPadding = contentPadding) {
        items(shown, key = { it.id }) { item -> SelectedAvatarChip(item, removeDescription(item.name), tapToRemove = tapToRemove) { onRemove(item.id) } }
        if (items.size > maxChips) {
            item {
                Box(Modifier.size(width = 54.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                    Text("+${items.size - maxChips}", color = c.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 已选的一个人 / 群：44dp 头像 + 右上 ✕ + 下面 11sp 名字。**唯一一份实现**——
 * 头像条（建群资料页、分享页底栏）与分享确认面板的网格都用它，只是格宽不同。
 *
 * @param cellWidth 一格的宽（条里 54dp；确认面板网格放宽到 64dp，名字少截一点）。
 */
@Composable
fun SelectedAvatarChip(
    item: StripItem,
    removeDescription: String,
    modifier: Modifier = Modifier,
    cellWidth: Dp = 54.dp,
    tapToRemove: Boolean = false,
    onRemove: () -> Unit,
) {
    val c = IMTheme.colors
    val tap = if (tapToRemove) Modifier.clickable(onClick = onRemove) else Modifier
    Column(modifier.width(cellWidth).then(tap).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            IMAvatar(item.name, seed = item.seed, avatarUrl = item.avatarUrl, size = 44.dp)
            Box(
                Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(c.neutralControl)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Image(Lucide.X, removeDescription, Modifier.size(10.dp), colorFilter = ColorFilter.tint(c.textSecondary))
            }
        }
        Text(item.name, color = c.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
