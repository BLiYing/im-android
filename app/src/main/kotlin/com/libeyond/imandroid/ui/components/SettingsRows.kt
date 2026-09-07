package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 分组设置表的基础件（对齐 iOS `UITableViewStyleInsetGrouped`）。
 *
 * **纯展示，不持业务状态**（CODING_STYLE §7②）：一行长什么样由参数决定，
 * 点了干什么由调用方给。新增设置项 = 调用方多写一行 [IMSettingsRow]，这里不改。
 */

/** 分组标题（iOS 的 `titleForHeaderInSection`）。空串不渲染，不留空白行。 */
@Composable
fun IMSectionHeader(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(
        text = text,
        color = IMTheme.colors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(
            start = IMTheme.dimens.space4 + 4.dp,
            end = IMTheme.dimens.space4,
            top = IMTheme.dimens.space4,
            bottom = 6.dp,
        ),
    )
}

/** 分组脚注（iOS 的 `titleForFooterInSection`），用于解释危险操作的后果。 */
@Composable
fun IMSectionFooter(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(
        text = text,
        color = IMTheme.colors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(
            start = IMTheme.dimens.space4 + 4.dp,
            end = IMTheme.dimens.space4 + 4.dp,
            top = 6.dp,
        ),
    )
}

/** 一个圆角白卡分组。子项之间的分割线由调用方用 [IMRowDivider] 插入。 */
@Composable
fun IMSettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IMTheme.dimens.space4)
            .clip(RoundedCornerShape(IMTheme.dimens.radiusCard))
            .background(IMTheme.colors.cardBackground),
        content = content,
    )
}

/**
 * 组内分割线。**左缩进到标题起点**（图标宽 + 间距），与 iOS `separatorInset` 同口径——
 * 通栏分割线会把彩色图标那一列切开，看着像两张表。
 */
@Composable
fun IMRowDivider(insetStart: androidx.compose.ui.unit.Dp = Dp.Unspecified) {
    val inset = if (insetStart == Dp.Unspecified) IMTheme.dimens.settingsSeparatorInset else insetStart
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(0.5.dp)
            .background(IMTheme.colors.separator),
    )
}

/**
 * 一行设置：彩色圆角图标方块 + 标题 +（可选）右值 + chevron。
 *
 * @param icon null 时不占图标位（iOS 的「退出登录」行就是这样），标题直接顶到左边距。
 * @param destructive 红字且不显 chevron —— 危险项不是「进下一页」，画个箭头是误导。
 */
@Composable
fun IMSettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconBackground: Color = Color.Unspecified,
    rightValue: String = "",
    destructive: Boolean = false,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = d.settingsRowHeight)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(d.settingsIcon)
                    .clip(RoundedCornerShape(d.radiusSettingsIcon))
                    .background(if (iconBackground == Color.Unspecified) c.accent else iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(d.settingsIconGlyph),
                    // 图标恒白：底是确定的深色方块（Tokens 顶部允许的白色用法之一）
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
            Spacer(Modifier.width(d.space3))
        }
        Text(
            text = title,
            color = if (destructive) c.danger else c.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        if (rightValue.isNotEmpty()) {
            Text(
                text = rightValue,
                color = c.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (!destructive) {
            Image(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(c.textTertiary),
            )
        }
    }
}

/**
 * 「键 : 值」信息行（设备详情卡、资料只读卡共用）。
 * @param valueColor 传 [Color.Unspecified] 用默认次要色；在线态那类要单独上色时才传。
 */
@Composable
fun IMKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    val c = IMTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IMTheme.dimens.space4, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = if (valueColor == Color.Unspecified) c.textPrimary else valueColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
