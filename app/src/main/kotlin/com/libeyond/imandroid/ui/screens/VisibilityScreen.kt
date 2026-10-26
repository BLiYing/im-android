package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.libeyond.imandroid.data.Visibility
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 「谁能看到」档位页（上次上线 / 头像共用；PRIVACY_VISIBILITY_DESIGN §4）：所有人 / 我的联系人 / 无人，当前项 ✓。
 * 版式复用自动删除档位页的 [LanguageOptionRow]；选中即回调，保存 / 回滚 / 返回都在 Host，本页纯展示。
 */
@Composable
fun VisibilityScreen(title: String, footer: String, current: Visibility?, onSelect: (Visibility) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(IMTheme.colors.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = title, onLeft = onBack)
        Spacer(Modifier.height(IMTheme.dimens.sectionGap))
        IMSettingsGroup {
            Visibility.ORDER.forEachIndexed { i, v ->
                if (i > 0) IMRowDivider()
                LanguageOptionRow(title = v.label, selected = v == current, onClick = { onSelect(v) })
            }
        }
        IMSectionFooter(footer)
    }
}
