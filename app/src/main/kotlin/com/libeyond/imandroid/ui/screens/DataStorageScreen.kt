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
import com.composables.icons.lucide.Antenna
import com.composables.icons.lucide.ChartPie
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wifi
import com.libeyond.imandroid.data.DownloadNetwork
import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.data.DownloadSettings
import com.libeyond.imandroid.data.DownloadSettingsUi
import com.libeyond.imandroid.ui.components.IMActionRow
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 「数据和存储」主页（对齐 iOS `IMDataStorageViewController`）：
 * ① 存储用量（点了是清除缓存） ② 自动下载媒体文件：使用移动数据 / 使用 Wi-Fi / 重置。
 *
 * 纯展示，状态与副作用在 `DataStorageHost`。
 */
@Composable
fun DataStorageScreen(
    /** 已下载媒体 + 图片缓存的总字节；还没量出来为 null。 */
    cacheBytes: Long?,
    settings: DownloadSettings,
    onBack: () -> Unit,
    onStorageUsage: () -> Unit,
    onOpenNetwork: (DownloadNetwork) -> Unit,
    onReset: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = "数据和存储", onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.sectionGap))
            IMSettingsGroup {
                IMSettingsRow(
                    title = "存储用量",
                    onClick = onStorageUsage,
                    icon = Lucide.ChartPie,
                    iconBackground = ic.orange,
                    rightValue = DownloadSettingsUi.usageLabel(cacheBytes),
                )
            }
            IMSectionFooter("下载的文件缓存在本机；清除后云端仍保留，需要时可重新下载。")

            IMSectionHeader("自动下载媒体文件")
            IMSettingsGroup {
                DownloadNetwork.entries.forEachIndexed { i, net ->
                    if (i > 0) IMRowDivider()
                    val cellular = net == DownloadNetwork.Cellular
                    IMSettingsRow(
                        title = net.title,
                        onClick = { onOpenNetwork(net) },
                        icon = if (cellular) Lucide.Antenna else Lucide.Wifi,
                        iconBackground = if (cellular) ic.green else ic.blue,
                        subtitle = DownloadSettingsUi.networkSummary(DownloadSettingsUi.policyOf(settings, net)),
                    )
                }
                IMRowDivider()
                // 已是出厂默认（含刚点过重置）→ 无可重置：置灰不可点，改过之后自动恢复（iOS 同）
                IMActionRow(
                    title = "重置自动下载设置",
                    onClick = onReset,
                    enabled = !DownloadPolicy.isDefault(settings),
                    alignToIconRows = true,
                )
            }
            IMSectionFooter("“重置”会把两个网络都恢复为出厂默认（移动数据中档、Wi-Fi 高档）。语音消息占用小，始终自动下载。")
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}
