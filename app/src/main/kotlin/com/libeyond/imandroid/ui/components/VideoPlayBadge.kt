package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 视频封面中心的播放角标。单条视频气泡与相册宫格的视频格共用这一份——
 * 各画各的，迟早一个深一个浅、一个带底一个不带（iOS 两处也是同一个 `play.circle.fill`）。
 * 宫格格子窄（3 列时约 79dp），调用方传小一号的尺寸。
 */
@Composable
fun VideoPlayBadge(
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    iconSize: Dp = 20.dp,
) {
    val c = IMTheme.colors
    Box(
        modifier = modifier.size(diameter).clip(CircleShape).background(c.overlay),
        contentAlignment = Alignment.Center,
    ) {
        Image(Lucide.Play, "播放", Modifier.size(iconSize), colorFilter = ColorFilter.tint(c.onMedia))
    }
}
