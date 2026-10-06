package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.libeyond.imandroid.ui.theme.IMTheme

/** 图标槽宽 = 头像宽（LIST_ENTRY_ROW_DESIGN §2.1）；槽 + 间距 12 → 文字左缘 = 16 + 40 + 12 = 68。 */
internal val ENTRY_SLOT = 40.dp
private val ENTRY_ICON = 22.dp
private val ENTRY_MIN_HEIGHT = 60.dp
private val ENTRY_DIVIDER_INSET = 68.dp

/**
 * 列表首行「入口行」（添加例外 / 搜索成员 / 添加成员 / 添加管理员）：
 * 槽 40dp（[icon] 居中）+ 间距 12 + accent 文字（`titleMedium` 16sp），左边距 16，行 min 高 60（不定高，大字号不裁字）。
 * 图标圆心与下面头像圆心同一竖线、文字左缘与名字对齐。
 *
 * [icon] 是槽内内容：线性图标用 [AccentLineIcon]，「添加例外」用 [AccentCircleIcon]。
 * [divider] = 行底画 68 起的分割线（调用方自己画分割线的列表传 false）。
 */
@Composable
internal fun LeadingEntryRow(
    icon: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
    showChevron: Boolean = false,
    background: Color = IMTheme.colors.cardBackground,
    divider: Boolean = false,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Box(Modifier.fillMaxWidth().background(background)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .defaultMinSize(minHeight = ENTRY_MIN_HEIGHT)
                .padding(horizontal = d.space4, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(ENTRY_SLOT), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(d.space3))
            Text(
                title, color = c.accent, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (showChevron) Text("›", color = c.textTertiary)
        }
        if (divider) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(0.5.dp)
                    .padding(start = ENTRY_DIVIDER_INSET).background(c.separator),
            )
        }
    }
}

/** 无底板 accent 线性图标（22dp），放进 [LeadingEntryRow] 的槽。 */
@Composable
internal fun AccentLineIcon(icon: ImageVector) {
    Image(icon, null, Modifier.size(ENTRY_ICON), colorFilter = ColorFilter.tint(IMTheme.colors.accent))
}

/** 「添加例外」的实心 accent 圆（32dp）+ 白色 ＋（16dp）。 */
@Composable
internal fun AccentCircleIcon(icon: ImageVector = Lucide.Plus) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(IMTheme.colors.accent), contentAlignment = Alignment.Center) {
        Image(icon, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(IMTheme.colors.onAccent))
    }
}
