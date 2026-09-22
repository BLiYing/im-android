package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMSecondaryButton
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 扫码登录确认页（QR P1 手机侧，对齐 iOS `IMQRLoginConfirmViewController`）。
 *
 * 手机扫到网页版登录码后，把设备/IP/位置摊开给用户核对——这四条是用户识破
 * 「被骗扫码」的唯一依据，不能只有一句「确认登录网页版？」就完事。
 */
@Composable
fun QrLoginConfirmScreen(
    device: String,
    ip: String,
    location: String,
    scanTime: String,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground)) {
        IMTopBar(title = "网页版登录确认", onLeft = onBack, showDivider = false)

        Column(Modifier.fillMaxWidth().padding(horizontal = d.space4), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Lucide.Monitor, contentDescription = null, tint = c.accent,
                modifier = Modifier.padding(top = 10.dp).size(48.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "确认登录网页版",
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "有设备正在用你的账号登录网页版，请核对下方信息",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground),
            ) {
                InfoRow("设备", device.ifBlank { "未知设备" })
                Divider()
                InfoRow("IP 地址", ip.ifBlank { "未知" })
                Divider()
                InfoRow("大致位置", location.ifBlank { "未知" })
                Divider()
                InfoRow("扫码时间", scanTime)
            }

            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(c.danger.copy(alpha = 0.10f)).padding(11.dp),
            ) {
                Text(
                    "不是你本人操作？请点「不是我，拒绝登录」，并尽快修改密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.danger,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.fillMaxWidth().padding(horizontal = d.space4).padding(bottom = 20.dp)) {
            IMPrimaryButton("确认登录", onConfirm, enabled = !submitting)
            Spacer(Modifier.height(10.dp))
            IMSecondaryButton("不是我，拒绝登录", onReject, enabled = !submitting)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(IMTheme.colors.separator))
}
