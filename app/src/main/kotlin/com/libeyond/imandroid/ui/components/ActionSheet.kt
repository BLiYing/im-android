package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme

/** 底部动作菜单的一项。 */
data class SheetItem(
    val label: String,
    val destructive: Boolean = false,
    /**
     * 行图标。iOS 的长按菜单**每一项都有图标**（SF Symbol），只有文字的菜单在观感上差一截。
     * Android 用 Lucide 里语义最近的一枚（见 `docs/UI_PARITY_IOS.md` §3 那条同样的说明）。
     */
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /**
     * 子菜单。非空时点这一项**不执行动作、改为在原位展开子项**（对齐 iOS `UIMenu` 的
     * inline submenu）——「删除」就是这么用的：两档删除是一个动作的两种范围，
     * 摊成两个平级项会让人以为是两件事。
     */
    val submenu: List<SheetItem> = emptyList(),
    /** 依赖媒体字节（复制 / 收藏 / 转发 / 下载）：查看器当前页已失效时由查看器滤掉。 */
    val needsMedia: Boolean = false,
    /** 单选态：右侧打 ✓（自动删除「自定义时间」弹窗用）。 */
    val checked: Boolean = false,
    val onClick: () -> Unit = {},
)

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
                // 行很多时整体不超过屏高 65%（其余留给遮罩），中间行区滚动
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
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
            // 行多（如 18 档）时可滚动；标题与「取消」固定在两端
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                item.onClick()
                                onDismiss()
                            },
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            text = item.label,
                            color = if (item.destructive) c.danger else c.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        )
                        if (item.checked) {
                            Image(
                                Lucide.Check, null,
                                Modifier.padding(end = d.space4).height(18.dp),
                                colorFilter = ColorFilter.tint(c.accent),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                }
            }
            Text(
                text = stringResource(R.string.common_cancel),
                color = c.textSecondary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 14.dp),
            )
        }
    }
}
