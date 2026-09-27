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
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DownloadCategory
import com.libeyond.imandroid.data.DownloadNetwork
import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.data.DownloadSettingsUi
import com.libeyond.imandroid.data.NetworkPolicy
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMStepSlider
import com.libeyond.imandroid.ui.components.IMSwitchRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 自动下载 ▸ 使用移动数据 / 使用 Wi-Fi（对齐 iOS `IMAutoDownloadNetworkViewController`）：
 * 总开关 → 流量档位（低/中/高，对不上预设时临时多出「自定义」）→ 图片/视频/文件三类入口。
 *
 * 总开关关掉时档位滑杆置灰禁拖，但三类入口仍可进（iOS 同）——关着总开关也能先把细项调好。
 */
@Composable
fun AutoDownloadNetworkScreen(
    network: DownloadNetwork,
    policy: NetworkPolicy,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onCommitTier: (Int) -> Unit,
    onOpenCategory: (DownloadCategory) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val tier = DownloadPolicy.tierOf(policy)
    val names = DownloadSettingsUi.tierNames(custom = tier == DownloadPolicy.Tier.Custom)

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = network.title, onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.sectionGap))
            IMSettingsGroup {
                IMSwitchRow(
                    title = stringResource(R.string.autodl_master_switch),
                    checked = policy.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            IMSectionHeader(stringResource(R.string.autodl_section_traffic_tier))
            IMSettingsGroup {
                IMStepSlider(
                    // title 是普通 (Int) -> String，非 @Composable，取文案走 Str.s
                    title = { Str.s(R.string.autodl_traffic_usage_prefix, names[it]) },
                    index = tier.ordinal,
                    count = names.size,
                    onCommit = onCommitTier,
                    tickLabels = names,
                    enabled = policy.enabled,
                )
            }
            IMSectionFooter(stringResource(R.string.autodl_footer_traffic_tier))

            IMSectionHeader(stringResource(R.string.autodl_section_media_types))
            IMSettingsGroup {
                DownloadCategory.entries.forEachIndexed { i, cat ->
                    if (i > 0) IMRowDivider(insetStart = d.space4)
                    IMSettingsRow(
                        title = cat.title,
                        onClick = { onOpenCategory(cat) },
                        rightValue = DownloadSettingsUi.categoryValue(policy, cat),
                    )
                }
            }
            IMSectionFooter(stringResource(R.string.autodl_footer_media_types))
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}
