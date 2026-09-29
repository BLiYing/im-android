package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatThemeId
import com.libeyond.imandroid.data.ChatWallpaper
import com.libeyond.imandroid.ui.components.ChatWallpaperBackground
import com.libeyond.imandroid.ui.theme.DarkIMColors
import com.libeyond.imandroid.ui.theme.IMColors
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.theme.LightIMColors
import com.libeyond.imandroid.ui.theme.themedColors

/**
 * 聊天预览（对齐 iOS `IMAppearanceChatPreview`）：壁纸 + 标题 +「今天」胶囊 + 三个气泡。
 *
 * 主题/壁纸/字号/圆角**全由参数给**，不读当前偏好——同一个组件既画主页的实时预览，也画
 * 网格里「换成这个主题会怎样」的缩略图（UI_COLOR §5：必须用真实聊天预览，不能只给文字）。
 * 预览里的气泡四角同圆角、不画尾巴，与 iOS 一致。
 *
 * @param expanded 滑块页里铺满用：不显示「聊天预览」标题。
 * @param scale 缩略图整体缩放（iOS 网格 0.72，整张 transform 缩放），字号、间距、图案一起缩。
 */
@Composable
fun AppearanceChatPreview(
    theme: ChatThemeId,
    wallpaper: ChatWallpaper,
    fontSize: Int,
    bubbleRadius: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    scale: Float = 1f,
) {
    val colors = previewColors(theme)
    Box(modifier) {
        ChatWallpaperBackground(
            Modifier.matchParentSize(),
            style = wallpaper,
            top = colors.wallpaperTop,
            bottom = colors.wallpaperBottom,
            doodle = colors.wallpaperDoodle,
            patternScale = scale,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp * scale)) {
                if (!expanded) {
                    Spacer(Modifier.height(14.dp * scale))
                    Text(
                        stringResource(R.string.appearance_preview_title),
                        color = colors.textPrimary,
                        fontSize = (16 * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp * scale))
                Text(
                    stringResource(R.string.time_today),
                    color = colors.onMedia,
                    fontSize = (11 * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(11.dp * scale))
                        .background(colors.accent.copy(alpha = 0.64f))
                        .padding(horizontal = 10.dp * scale, vertical = 3.dp * scale),
                )
                Spacer(Modifier.height(14.dp * scale))
                val bubble = @Composable { text: String, mine: Boolean, maxFrac: Float ->
                    PreviewBubble(text, mine, colors, fontSize, bubbleRadius.dp, w * maxFrac, scale)
                }
                bubble(stringResource(R.string.appearance_preview_bubble_incoming_1), false, 0.72f)
                Spacer(Modifier.height(10.dp * scale))
                bubble(stringResource(R.string.appearance_preview_bubble_outgoing), true, 0.84f)
                Spacer(Modifier.height(10.dp * scale))
                bubble(stringResource(R.string.appearance_preview_bubble_incoming_2), false, 0.76f)
            }
        }
    }
}

@Composable
private fun PreviewBubble(text: String, mine: Boolean, colors: IMColors, fontSize: Int, radius: Dp, maxWidth: Dp, scale: Float) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            color = colors.textPrimary,
            fontSize = (fontSize * scale).sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(radius * scale))
                .background(if (mine) colors.bubbleMe else colors.bubbleThem)
                .padding(horizontal = 13.dp * scale, vertical = 9.dp * scale),
        )
    }
}

/** 某主题在**当前明暗**下的令牌（预览/缩略图用，不改全局）。 */
@Composable
fun previewColors(theme: ChatThemeId): IMColors {
    val dark = IMTheme.colors.isDark
    return themedColors(if (dark) DarkIMColors else LightIMColors, theme, dark)
}

/**
 * 主题条里的一格（iOS 横向主题条的 mini 按钮，92 宽）：上下渐变底 + 两个小气泡 + 底部名字，
 * 选中时描一圈该主题自己的强调色。
 */
@Composable
fun ThemeMiniCard(theme: ChatThemeId, name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = previewColors(theme)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(width = 92.dp, height = 88.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.wallpaperTop, colors.wallpaperBottom)))
            .then(if (selected) Modifier.border(3.dp, colors.accent, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = 13.dp).size(43.dp, 18.dp)
                .clip(RoundedCornerShape(9.dp)).background(colors.bubbleMe),
        )
        Box(
            Modifier.offset(x = 12.dp, y = 39.dp).size(48.dp, 18.dp)
                .clip(RoundedCornerShape(9.dp)).background(colors.bubbleThem),
        )
        Text(
            name,
            // 名字压在半透明黑条上，恒白（Tokens 顶部允许的「确定深色底上的白字」）
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 7.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.overlay)
                .padding(vertical = 1.dp),
        )
    }
}
