package com.libeyond.imandroid.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.File
import com.composables.icons.lucide.IdCard
import com.composables.icons.lucide.Image as LucideImage
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.AttachItems
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 输入栏 ➕ 面板（微信式：**在输入栏下方**顶起，与键盘互斥）。
 *
 * 清单与顺序在 [AttachItems]（跨端契约，有测试钉着）；这里只管画。
 * 几何取 iOS 同值：面板高 236、格子 56×56、2×3。
 */
@Composable
internal fun AttachPanel(onPick: (AttachItems.Kind) -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(AttachItems.PANEL_HEIGHT.dp)
            // 面板是「一级表面」，与输入栏同层（UI_COLOR §2）
            .background(c.surface)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(d.space4),
    ) {
        AttachItems.ALL.chunked(AttachItems.COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, item ->
                    if (i > 0) Spacer(Modifier.weight(1f))
                    AttachCell(item, onPick)
                }
                // 最后一行不足一整行时补空位，免得三个格子被均分拉开
                repeat(AttachItems.COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(AttachItems.ITEM_SIZE.dp))
                }
            }
        }
    }
}

@Composable
private fun AttachCell(item: AttachItems.Item, onPick: (AttachItems.Kind) -> Unit) {
    val c = IMTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(AttachItems.ITEM_SIZE.dp)
                // 与面板本身（surface，一级表面）分层：方块是叠在它上面的卡片，补一层轻阴影
                // 才有"按钮"的立体感——之前用 pageBackground 纯色块贴着 surface，两层灰度
                // 太接近，显得扁平（用户反馈"图标好丑"，同 CallHistorySegment 选中态那颗
                // 立体药丸用的是同一套 surfaceElevated + shadow 手法）。
                .shadow(1.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(c.surfaceElevated)
                .clickable { onPick(item.kind) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = iconOf(item.kind),
                contentDescription = item.title,
                modifier = Modifier.size(28.dp),
                // 未实现的项**不置灰**：置灰等于说「这里坏了」，而它只是还没做。
                // 点进去给一句「还没做」比一个点不动的灰块清楚（同「我」页入口列表的做法）。
                // 用 textPrimary 而不是 textSecondary：方块底变亮后次要色线条太淡，衬不出图标。
                colorFilter = ColorFilter.tint(c.textPrimary),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = c.textSecondary, fontSize = 12.sp)
    }
}

private fun iconOf(kind: AttachItems.Kind): ImageVector = when (kind) {
    AttachItems.Kind.Photo -> Lucide.LucideImage
    AttachItems.Kind.Camera -> Lucide.Camera
    AttachItems.Kind.Favorite -> Lucide.Bookmark
    AttachItems.Kind.ContactCard -> Lucide.IdCard
    AttachItems.Kind.File -> Lucide.File
}
