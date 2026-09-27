package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.QrLoginConfirmScreen
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 扫码登录确认页接线层（QR P1 手机侧，对齐 iOS `IMQRLoginConfirmViewController`）。
 *
 * [onDone] 在确认/拒绝成功后回调一句 toast 文案，调用方（[QrRouteHost]）负责关掉本页 + 展示它
 * ——本页自己不持有全局 toast 状态。
 */
@Composable
internal fun QrLoginConfirmHost(
    client: IMClient,
    ticket: String,
    device: String,
    ip: String,
    location: String,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // 扫码时间＝进本页那一刻，与 iOS `nowTimeString` 同一口径（服务端 scan 接口不回时间戳）。
    val scanTime = remember { LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) }

    QrLoginConfirmScreen(
        device = device,
        ip = ip,
        location = location,
        scanTime = scanTime,
        submitting = submitting,
        onConfirm = {
            if (submitting) return@QrLoginConfirmScreen
            submitting = true
            scope.launch {
                runCatchingCancellable { client.qr.loginConfirm(ticket) }
                    .onSuccess { onBack(); onDone(Str.s(R.string.qr_login_confirm_confirmed_toast)) }
                    .onFailure { e -> submitting = false; error = e.userMessage(Str.s(R.string.qr_login_confirm_confirm_failed)) }
            }
        },
        onReject = {
            if (submitting) return@QrLoginConfirmScreen
            submitting = true
            scope.launch {
                runCatchingCancellable { client.qr.loginReject(ticket) }
                    .onSuccess { onBack(); onDone(Str.s(R.string.qr_login_confirm_rejected_toast)) }
                    .onFailure { e -> submitting = false; error = e.userMessage(Str.s(R.string.qr_login_confirm_reject_failed)) }
            }
        },
        onBack = onBack,
    )

    error.takeIf { it.isNotEmpty() }?.let { IMToast(it) { error = "" } }
}
