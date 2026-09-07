package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.DeviceDisplay
import com.libeyond.imandroid.sdk.api.DeviceSession
import com.libeyond.imandroid.ui.components.IMKeyValueRow
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 设备详情（对齐 iOS `IMDeviceDetailViewController`）：一张信息卡 + 底部「退出登录该设备」。
 *
 * **只对非本机设备开放**（列表页已挡住本机行）：退出本机等于退出登录，
 * 那条路在「我」页最下面，不该在这里再开一个语义不同的同名按钮。
 */
@Composable
fun DeviceDetailScreen(
    device: DeviceSession,
    now: Long,
    submitting: Boolean,
    onRevoke: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = "设备详情", onLeft = onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = DeviceDisplay.platformEmoji(device.platform),
                fontSize = 34.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = DeviceDisplay.deviceName(device),
                color = c.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(22.dp))

            IMSettingsGroup {
                val statusText = if (device.online) "在线" else DeviceDisplay.lastActiveText(device.lastActiveAt, now)
                IMKeyValueRow("状态", statusText, valueColor = if (device.online) c.online else c.textSecondary)
                IMRowDivider(insetStart = d.space4)
                IMKeyValueRow("类型", DeviceDisplay.typeText(device))
                IMRowDivider(insetStart = d.space4)
                IMKeyValueRow("登录时间", DeviceDisplay.loginTimeText(device.createdAt))
                IMRowDivider(insetStart = d.space4)
                IMKeyValueRow(
                    "最近活跃",
                    if (device.online) "当前在线" else DeviceDisplay.lastActiveText(device.lastActiveAt, now),
                )
                IMRowDivider(insetStart = d.space4)
                IMKeyValueRow("IP 地址", device.loginIp.ifBlank { "未知" })
                IMRowDivider(insetStart = d.space4)
                IMKeyValueRow("大致位置", device.loginLoc.ifBlank { "未知" })
            }

            Text(
                text = "位置由 IP 粗略反查，仅供识别，不参与鉴权。",
                color = c.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        Text(
            text = if (submitting) "退出中…" else "退出登录该设备",
            color = c.onAccent,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.space4)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .clip(RoundedCornerShape(12.dp))
                .background(if (submitting) c.danger.copy(alpha = 0.6f) else c.danger)
                .clickable(enabled = !submitting, onClick = onRevoke)
                .padding(vertical = 15.dp),
        )
    }
}
