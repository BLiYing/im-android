package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.ChatThemeId
import com.libeyond.imandroid.data.ChatWallpaper
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 网格里的一格：用哪套主题 + 哪种壁纸画缩略图、叫什么、是不是当前选中。 */
class AppearanceGridItem(
    val key: String,
    val theme: ChatThemeId,
    val wallpaper: ChatWallpaper,
    val name: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * 聊天主题 / 聊天壁纸两张网格页共用（对齐 iOS `IMAppearanceGridViewController`）：两列，
 * 每格一张真实聊天缩略图（字号 12、圆角 12、整体 0.72 倍）+ 名字，选中项右上角打勾、名字变强调色。
 * 点一下立即生效、不需要确认（iOS 同）。主题网格用当前壁纸画、壁纸网格用当前主题画。
 */
@Composable
fun AppearanceGridScreen(title: String, items: List<AppearanceGridItem>, onBack: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = title, onLeft = onBack)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.key }) { GridCell(it) }
        }
    }
}

@Composable
private fun GridCell(item: AppearanceGridItem) {
    val c = IMTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().height(224.dp).clip(shape).background(c.cardBackground).clickable(onClick = item.onClick),
    ) {
        Box {
            AppearanceChatPreview(
                theme = item.theme,
                wallpaper = item.wallpaper,
                fontSize = 12,
                bubbleRadius = 12,
                scale = 0.72f,
                modifier = Modifier.fillMaxWidth().height(178.dp),
            )
            if (item.selected) {
                Image(
                    Lucide.CircleCheck,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(c.accent),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(25.dp)
                        .clip(RoundedCornerShape(50)).background(c.cardBackground),
                )
            }
        }
        Text(
            item.name,
            color = if (item.selected) c.accent else c.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
