package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 蓝色数字角标（`unreadBadge`）：个位数是正圆，两位数拉成胶囊；0 不画，>99 写「99+」。
 * 通讯录入口行与底栏「通讯录」Tab 共用。
 */
@Composable
fun UnreadCapsule(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val c = IMTheme.colors
    val h = IMTheme.dimens.unreadBadgeHeight
    Box(
        modifier = modifier.height(h).widthIn(min = h).clip(CircleShape).background(c.unreadBadge)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = c.onAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
