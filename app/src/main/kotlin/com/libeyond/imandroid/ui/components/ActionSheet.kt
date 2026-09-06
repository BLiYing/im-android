package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.ui.theme.IMTheme

/** 底部动作菜单的一项。 */
data class SheetItem(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * 底部动作菜单——**数据驱动**（CHAT_UX §14）：新增一项 = 数组加一项，
 * 不是再复制一遍整个弹窗外壳。
 *
 * 危险项排最后并染 danger 色（destructive-last，避免误点）。
 */
@Composable
fun ActionSheet(
    title: String,
    items: List<SheetItem>,
    onDismiss: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.overlay)
            // 点遮罩关闭
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(d.space2)
                .clip(RoundedCornerShape(d.radiusCard))
                .background(c.surfaceElevated)
                .navigationBarsPadding()
                // 吞掉点击，别让菜单本体的点击穿透到遮罩把自己关了
                .clickable(enabled = false) {},
        ) {
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            }
            items.forEach { item ->
                Text(
                    text = item.label,
                    color = if (item.destructive) c.danger else c.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            item.onClick()
                            onDismiss()
                        }
                        .padding(vertical = 14.dp),
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            }
            Text(
                text = "取消",
                color = c.textSecondary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 14.dp),
            )
        }
    }
}
