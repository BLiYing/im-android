package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AutoDelete
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 自动删除档位选择页（AUTO_DELETE_DESIGN §3.3 B）。**账号默认**与**单个会话**共用这一页，
 * 只有页脚文案不同（[footer]）。版式整套复用语言页：分组卡 + [LanguageOptionRow]（标题 + 选中 ✓），不新造组件；
 * 选中即回调，保存 / 确认 / 回滚都在调用方（Host），本页纯展示。
 *
 * @param onLockedTap 非 null = 只读（群里非群主 / 管理员）：点任意行（含「自定义」）只回调它，不选中、不开弹窗、不触发 [onSelect]。
 */
@Composable
fun AutoDeleteScreen(current: Int, footer: String, onSelect: (Int) -> Unit, onBack: () -> Unit, onLockedTap: (() -> Unit)? = null) {
    val c = IMTheme.colors
    var customOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) { // 弹窗盖在整页之上
        Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
            IMTopBar(title = stringResource(R.string.ps_row_auto_delete_messages), onLeft = onBack)
            Spacer(Modifier.height(IMTheme.dimens.sectionGap))
            IMSettingsGroup {
                AutoDelete.PRESETS.forEach { sec ->
                    LanguageOptionRow(title = AutoDelete.label(sec), selected = sec == current, onClick = { onLockedTap?.invoke() ?: onSelect(sec) })
                    IMRowDivider()
                }
                CustomRow(current = current, onClick = { onLockedTap?.invoke() ?: run { customOpen = true } })
            }
            IMSectionFooter(footer)
        }
        if (customOpen) {
            ActionSheet(
                title = stringResource(R.string.autodel_custom_title),
                items = AutoDelete.CUSTOM_TIERS.map { sec ->
                    SheetItem(AutoDelete.label(sec), checked = sec == current) { onSelect(sec) }
                },
                onDismiss = { customOpen = false },
            )
        }
    }
}

/** 「自定义」行：未选中 = chevron；当前档位不在固定档时右值显示该时长并打 ✓（设计 §3.3 B）。 */
@Composable
private fun CustomRow(current: Int, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val selected = AutoDelete.isCustomSelected(current)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.autodel_custom), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Text(AutoDelete.label(current), color = c.textSecondary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            Image(Lucide.Check, null, Modifier.height(18.dp), colorFilter = ColorFilter.tint(c.accent))
        } else {
            Image(Lucide.ChevronRight, null, Modifier.height(18.dp), colorFilter = ColorFilter.tint(c.textTertiary))
        }
    }
}
