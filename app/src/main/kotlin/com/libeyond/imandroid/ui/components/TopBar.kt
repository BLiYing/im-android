package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
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
 * 标题栏右侧的**圆形图标钮**（通讯录「添加朋友」、消息页 ＋），经 [IMTopBar] 的 `right` 槽放进去。
 *
 * 对齐 iOS `IMLiquidNavigationBar` 的 `actionCircular`：iOS 26 是液态玻璃圆钮，旧系统是 `.gray()` 填充 +
 * 0.5pt 分割线描边——**图标用正文色，不用主色**。本端不做液态玻璃（UI_SPEC §6.4 的正当差异），
 * 取旧系统那一版。此前通讯录右上角是一枚主色的裸放大镜，与 iOS 一眼两样（2026-09-15 用户报）。
 * 尺寸见 `IMDimens.topBarCircleButton` 的注释（UI_SPEC §4.5）。
 */
@Composable
fun TopBarCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 栏高固定 [IMDimens.topBarHeight]，圆钮在栏内垂直居中即可
    Box(
        modifier = modifier
            .size(d.topBarCircleButton)
            .clip(CircleShape)
            .background(c.subtleFill)
            .border(0.5.dp, c.separator, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(d.topBarCircleIcon),
            colorFilter = ColorFilter.tint(c.textPrimary),
        )
    }
}

/**
 * 标题栏的**外框**：底色 + 最小高 [com.libeyond.imandroid.ui.theme.IMDimens.topBarHeight] + 左右内边距。
 * [IMTopBar]、聊天内搜索态顶栏、名片选择页的自带头共用——栏色或栏高要改只改这一处
 * （此前各抄一份，正是栏高漂移的由来）。用 `heightIn(min)` 而不是定高：系统字号放大到 1.5~2 倍时，
 * 17sp 标题 + 13sp 副标题超过 56dp，定高会把文字裁到状态栏 / 分割线上，宁可栏随字撑高。
 *
 * @param color 栏底色；[Color.Unspecified] = 跟随页面 `groupedBackground`。
 */
@Composable
fun Modifier.topBarChrome(color: Color = Color.Unspecified): Modifier {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    return this
        .fillMaxWidth()
        .background(if (color.isSpecified) color else c.groupedBackground)
        .heightIn(min = d.topBarHeight)
        .padding(horizontal = d.space3)
}

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
    leftDescription: String = stringResource(R.string.common_back),
    /** 左侧改用**文字**而不是图标（多选态的「取消」）。空 = 仍画 [leftIcon] 或默认返回箭头。 */
    leftLabel: String = "",
    onLeft: (() -> Unit)? = null,
    actionText: String = "",
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    avatar: TopBarAvatar? = null,
    /** 右侧自定义槽（如群列表的 `+`）。**优先级最低**——头像与文字动作都比它更常用。 */
    right: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    /**
     * 栏底色。默认 **跟随页面底色 [com.libeyond.imandroid.ui.theme.IMColors.groupedBackground]**：
     * 状态栏是透明的，它后面露出的是页面 Column 的底色，栏若另画 `surface`，深色下
     * （`#323232` 对 `#1F1F1F`）状态栏与标题栏就是两种颜色。对齐 iOS `IMLiquidNavigationBar`
     * 的底色取 `systemBackground`（与页面同色）。页面底色不是 groupedBackground 的页面自己传。
     */
    containerColor: Color = Color.Unspecified,
    /** 返回钮右上角的红圈数字（聊天页：**其它会话**的未读总数，对齐 iOS `backBadge`）。≤0 不显；>99 显「99+」。 */
    leftBadge: Int = 0,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = modifier.topBarChrome(containerColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(d.topBarSide), contentAlignment = Alignment.CenterStart) {
            if (onLeft != null && leftLabel.isNotEmpty()) {
                Text(
                    text = leftLabel,
                    color = c.accent,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable(onClick = onLeft),
                )
            } else if (onLeft != null) {
                Box {
                    Image(
                        imageVector = leftIcon ?: Lucide.ArrowLeft,
                        contentDescription = leftDescription,
                        modifier = Modifier.size(d.topBarIcon).clickable(onClick = onLeft),
                        colorFilter = ColorFilter.tint(c.accent),
                    )
                    if (leftBadge > 0) {
                        // 红圈白字 12sp 半粗、高 18dp；一位数是正圆，多位是药丸（宽 = max(18, 字宽+10)）。不可点
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-8).dp)
                                .heightIn(min = 18.dp).widthIn(min = 18.dp)
                                .clip(RoundedCornerShape(9.dp)).background(c.danger).padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (leftBadge > 99) "99+" else leftBadge.toString(),
                                color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
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
