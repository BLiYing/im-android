package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 标题栏右侧的圆形会话头像。传了它就顶掉 [IMTopBar] 的文字动作
 * （两者都在右侧那 [com.libeyond.imandroid.ui.theme.IMDimens.topBarSide] 宽的位置里，塞不下两个）。
 */
data class TopBarAvatar(
    val label: String,
    val seed: String,
    val url: String = "",
    val onClick: (() -> Unit)? = null,
)

/**
 * 全局唯一的顶部标题栏：左（返回或自定义图标）+ **居中**标题/副标题 + 右（文字动作或会话头像）。
 *
 * 规格见 `../IMServer/docs/UI_SPEC.md` §4.5，**改数字先改那张表**。
 * 对齐 iOS `IMLiquidNavigationBar`：标题 17 semibold 居中、副标题 13 regular 居中。
 *
 * **左右两侧固定占位、不用 weight 撑**：靠 weight 撑，标题会随左右内容长度左右漂——
 * 返回键旁多一个字、右边从「发送」变「发送(3)」，标题就跟着挪，翻页时肉眼可见地抖。
 * iOS 用 `centerX = (bounds.width - centerWidth) / 2` 算，同一个道理。
 *
 * 本端**不做** iOS 那套液态标题栏与滚动形变——那是 iOS 26 的原生观感，
 * 在 Compose 里手搓只会得到形似神不似的东西（UI_SPEC §6.4 的「正当差异」）。
 *
 * @param leftIcon 不传则用返回箭头；传了就是自定义左键（如「我」页的二维码）。
 * @param onLeft   不传则整个左键不渲染（但仍占位，标题才不会偏）。
 * @param onTitleClick 点标题的动作（iOS 靠点标题进会话详情）。
 */
@Composable
fun IMTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    /** 副标题用强调色（「在线」）。 */
    subtitleAccent: Boolean = false,
    leftIcon: ImageVector? = null,
    leftDescription: String = "返回",
    onLeft: (() -> Unit)? = null,
    actionText: String = "",
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    avatar: TopBarAvatar? = null,
    /** 右侧自定义槽（如群列表的 `+`）。**优先级最低**——头像与文字动作都比它更常用。 */
    right: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = d.space3, vertical = d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(d.topBarSide), contentAlignment = Alignment.CenterStart) {
            if (onLeft != null) {
                Image(
                    imageVector = leftIcon ?: Lucide.ArrowLeft,
                    contentDescription = leftDescription,
                    modifier = Modifier.size(d.topBarIcon).clickable(onClick = onLeft),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).let {
                if (onTitleClick != null) it.clickable(onClick = onTitleClick) else it
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = c.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = if (subtitleAccent) c.online else c.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(Modifier.width(d.topBarSide), contentAlignment = Alignment.CenterEnd) {
            when {
                // 头像优先：两者都在这一格里，塞不下两个
                avatar != null -> IMAvatar(
                    avatar.label,
                    seed = avatar.seed,
                    avatarUrl = avatar.url,
                    size = d.topBarAvatar,
                    modifier = if (avatar.onClick != null) {
                        Modifier.clickable(onClick = avatar.onClick)
                    } else {
                        Modifier
                    },
                )
                onAction != null && actionText.isNotEmpty() -> Text(
                    text = actionText,
                    color = if (actionEnabled) c.accent else c.textTertiary,
                    fontSize = 17.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable(enabled = actionEnabled, onClick = onAction),
                )
                right != null -> right()
            }
        }
    }
    if (showDivider) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
}
