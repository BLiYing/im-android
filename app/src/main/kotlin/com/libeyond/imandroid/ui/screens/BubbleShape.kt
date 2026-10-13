package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 气泡外轮廓（对齐 iOS `IMTheme applyBubbleDirectionStyle:mine:`）。
 *
 * 有「尾」的气泡（文本/文件/语音/链接卡/名片/通话/合并转发）：自己靠右 → **右下角直角**，
 * 对方靠左 → **左下角直角**，其余三角圆。纯图/视频气泡本身就是媒体（iOS 四角全圆），不带尾。
 *
 * 阴影（长按浮起）与裁剪必须用同一份形状，否则直角那一角会露出圆角阴影的缝。
 */
internal fun bubbleShape(radius: Dp, mine: Boolean, hasTail: Boolean): RoundedCornerShape =
    when {
        !hasTail -> RoundedCornerShape(radius)
        mine -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = radius, bottomEnd = 0.dp)
        else -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 0.dp, bottomEnd = radius)
    }
