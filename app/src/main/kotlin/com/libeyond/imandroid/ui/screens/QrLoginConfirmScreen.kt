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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.libeyond.imandroid.R
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
        IMTopBar(title = stringResource(R.string.qr_login_confirm_nav_title), onLeft = onBack, showDivider = false)

        Column(Modifier.fillMaxWidth().padding(horizontal = d.space4), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Lucide.Monitor, contentDescription = null, tint = c.accent,
                modifier = Modifier.padding(top = 10.dp).size(48.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.qr_login_confirm_title),
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.qr_login_confirm_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground),
            ) {
                InfoRow(stringResource(R.string.qr_login_confirm_row_device), device.ifBlank { stringResource(R.string.device_platform_unknown) })
                Divider()
                InfoRow(stringResource(R.string.qr_login_confirm_row_ip), ip.ifBlank { stringResource(R.string.common_unknown) })
                Divider()
                InfoRow(stringResource(R.string.qr_login_confirm_row_location), location.ifBlank { stringResource(R.string.common_unknown) })
                Divider()
                InfoRow(stringResource(R.string.qr_login_confirm_row_time), scanTime)
            }

            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(c.danger.copy(alpha = 0.10f)).padding(11.dp),
            ) {
                Text(
                    stringResource(R.string.qr_login_confirm_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.danger,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.fillMaxWidth().padding(horizontal = d.space4).padding(bottom = 20.dp)) {
            IMPrimaryButton(stringResource(R.string.qr_login_confirm_confirm), onConfirm, enabled = !submitting)
            Spacer(Modifier.height(10.dp))
            IMSecondaryButton(stringResource(R.string.qr_login_confirm_reject), onReject, enabled = !submitting)
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
