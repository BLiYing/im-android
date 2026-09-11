package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.libeyond.imandroid.data.CategoryRule
import com.libeyond.imandroid.data.DownloadCategory
import com.libeyond.imandroid.data.DownloadSettingsUi
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMStepSlider
import com.libeyond.imandroid.ui.components.IMSwitchRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 自动下载 ▸ 某网络 ▸ 图片/视频/文件（对齐 iOS `IMAutoDownloadCategoryViewController`）：
 * 单聊/群聊两个开关 +（视频、文件才有的）大小上限滑杆。
 *
 * 回调只报「改了哪一项」，不回传整条规则：新规则由 Host 基于 store 当前值算——
 * 在这里拿重组时的 [rule] 拼整条回去，连拨两个开关时后一次会把前一次改回去。
 */
@Composable
fun AutoDownloadCategoryScreen(
    category: DownloadCategory,
    rule: CategoryRule,
    onBack: () -> Unit,
    onSingleChange: (Boolean) -> Unit,
    onGroupChange: (Boolean) -> Unit,
    onCommitSize: (Int) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = category.title, onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            IMSectionHeader("自动下载${category.title}")
            IMSettingsGroup {
                IMSwitchRow(title = "单聊", checked = rule.single, onCheckedChange = onSingleChange)
                IMRowDivider(insetStart = d.space4)
                IMSwitchRow(title = "群聊", checked = rule.group, onCheckedChange = onGroupChange)
            }

            if (!category.hasSizeLimit) {
                IMSectionFooter("图片体积小，建议保持自动下载（无大小上限）。")
            } else {
                val saved = DownloadSettingsUi.sizeStopIndex(rule.maxBytes)
                IMSectionHeader("大小上限")
                IMSettingsGroup {
                    IMStepSlider(
                        // 停在已保存那一档时显示**真实值**：服务端的值不一定正好在档位上（iOS 进页同样先显示原值）
                        title = { i ->
                            val bytes = if (i == saved) rule.maxBytes else DownloadSettingsUi.SIZE_STOPS[i]
                            "上限 ${DownloadSettingsUi.sizeLabel(bytes)}"
                        },
                        index = saved,
                        count = DownloadSettingsUi.SIZE_STOPS.size,
                        onCommit = onCommitSize,
                    )
                }
                IMSectionFooter("超过上限的媒体不自动下载，卡片显“未下载”，可手动点 ↓。上限设为“关”即完全手动。")
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}
