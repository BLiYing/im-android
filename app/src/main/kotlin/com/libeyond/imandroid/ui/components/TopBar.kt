package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 顶部标题栏：左（返回或自定义图标）+ 居中标题 + 右（文字动作）。
 *
 * 对应 iOS 的 `UINavigationItem`（left/right BarButtonItem + title）。
 * 本端**不做** iOS 那套液态标题栏与滚动形变——那是 iOS 26 的原生观感，
 * 在 Compose 里手搓一套只会得到一个形似而神不似的东西（UI_SPEC §6.4 的「正当差异」）。
 *
 * @param leftIcon 不传则用返回箭头；传了就是自定义左键（如「我」页的二维码）。
 * @param onLeft   不传则整个左键不渲染。
 */
@Composable
fun IMTopBar(
    title: String,
    modifier: Modifier = Modifier,
    leftIcon: ImageVector? = null,
    leftDescription: String = "返回",
    onLeft: (() -> Unit)? = null,
    actionText: String = "",
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
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
        // 左右两侧都用固定宽度占位，标题才真的居中；靠 weight 撑会随文字长度左右漂。
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) {
            if (onLeft != null) {
                Image(
                    imageVector = leftIcon ?: Lucide.ArrowLeft,
                    contentDescription = leftDescription,
                    modifier = Modifier.size(24.dp).clickable(onClick = onLeft),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            }
        }
        Text(
            text = title,
            color = c.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            if (onAction != null && actionText.isNotEmpty()) {
                Text(
                    text = actionText,
                    color = if (actionEnabled) c.accent else c.textTertiary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clickable(enabled = actionEnabled, onClick = onAction),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
}
