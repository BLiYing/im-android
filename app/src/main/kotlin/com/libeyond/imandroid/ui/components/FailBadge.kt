package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 发送失败红点（对齐 iOS `IMFailBadgeView`）：直径 18 的红色实心圆 + 粗体 "!"（13），与气泡隔 6。
 *
 * [onRetry] 为 null = 不可点：被服务端明确拒收的消息重发必再被拒，红点照显但不响应
 * （iOS `tappable = NO`），恢复入口是气泡下方的说明行。
 *
 * 命中区外扩到 33×40（iOS `kIMFailBadgeTouchOutset`：上下左各 11、右 4）：18dp 圆点单独不好按。
 * 两种态占位相同，红点位置不随可点性跳；垂直居中由调用方在 Row 里负责（`Alignment.CenterVertically`）。
 * "!" 不随系统字号缩放——圆是定径的，放大会溢出。
 */
@Composable
fun FailBadge(onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val a11y = stringResource(R.string.chat_message_fail_badge_a11y)
    Box(
        modifier = modifier
            .padding(end = 4.dp)
            .size(width = 33.dp, height = 40.dp)
            .then(
                if (onRetry != null) {
                    Modifier.clickable(onClick = onRetry).semantics { role = Role.Button; contentDescription = a11y }
                } else Modifier,
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier.padding(end = 2.dp).size(18.dp).clip(CircleShape).background(c.danger),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "!",
                color = c.onDanger,
                fontSize = with(LocalDensity.current) { 13.dp.toSp() },
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
