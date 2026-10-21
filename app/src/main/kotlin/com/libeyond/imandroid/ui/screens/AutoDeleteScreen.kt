package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
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
 */
@Composable
fun AutoDeleteScreen(current: Int, footer: String, onSelect: (Int) -> Unit, onBack: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.ps_row_auto_delete_messages), onLeft = onBack)
        Spacer(Modifier.height(IMTheme.dimens.sectionGap))
        IMSettingsGroup {
            AutoDelete.TIERS.forEachIndexed { i, sec ->
                if (i > 0) IMRowDivider()
                LanguageOptionRow(title = AutoDelete.label(sec), selected = sec == current, onClick = { onSelect(sec) })
            }
        }
        IMSectionFooter(footer)
    }
}
