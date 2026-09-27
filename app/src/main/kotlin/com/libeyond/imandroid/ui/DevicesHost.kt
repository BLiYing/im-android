package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.DeviceDisplay
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.DeviceSession
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.screens.DeviceDetailScreen
import com.libeyond.imandroid.ui.screens.DeviceListScreen
import com.libeyond.imandroid.ui.screens.REVOKE_ALL
import kotlinx.coroutines.launch

/**
 * 已登录设备（多设备管理 P2）：列表 → 详情 → 逐台踢下线 / 退出其他所有设备。
 *
 * 状态与副作用都在这里，两个 Screen 保持纯展示（CODING_STYLE §7②）。
 */
@Composable
fun DevicesHost(client: IMClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<DeviceSession>?>(null) }
    var error by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<DeviceSession?>(null) }
    var confirmOthers by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<DeviceSession?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    /**
     * 「现在」的取值时刻。**跟着列表一起刷新**，不用每帧的 `System.currentTimeMillis()`
     * ——后者会让「3 分钟前活跃」在滚动时抖动，也会让重组变得不稳定。
     */
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    suspend fun reload() {
        runCatchingCancellable { client.devices.list() }
            .onSuccess { devices = it; error = ""; now = System.currentTimeMillis() }
            .onFailure {
                // 刷新失败**保留当前内容**（与 iOS 同）：把已经看到的列表清空换一行报错，
                // 只会让用户以为设备都没了。
                if (devices == null) devices = emptyList()
                error = it.userMessage(Str.s(R.string.net_fallback_devices_load))
            }
    }

    LaunchedEffect(Unit) { reload() }

    // push 转场。**内容按转场自己的状态画**（`open`，不是 `detail`）：返回时 detail 已置空，
    // 滑走中的那一页拿到的仍是它自己那台设备，不会半路变成列表
    PushTransition(
        targetState = detail,
        depthOf = { if (it == null) 0 else 1 },
        contentKey = { it?.sessionId },
    ) { open ->
        if (open != null) {
            BackHandler { detail = null }
            DeviceDetailScreen(
                device = open,
                now = now,
                submitting = revoking == open.sessionId,
                onRevoke = { confirmOne = open },
                onBack = { detail = null },
            )
        } else {
            BackHandler(onBack = onBack)
            DeviceListScreen(
                devices = devices,
                error = error,
                revoking = revoking,
                now = now,
                onRefresh = { devices = null; scope.launch { reload() } },
                onOpenDetail = { detail = it },
                onRevokeOthers = { confirmOthers = true },
                onBack = onBack,
            )
        }
    }

    val one = confirmOne
    if (one != null) {
        IMConfirmDialog(
            title = stringResource(R.string.device_detail_revoke_confirm_title),
            message = stringResource(R.string.device_detail_revoke_confirm_message, DeviceDisplay.deviceName(one)),
            confirmText = stringResource(R.string.settings_logout),
            onConfirm = {
                revoking = one.sessionId
                scope.launch {
                    runCatchingCancellable { client.devices.revoke(one.sessionId) }
                        .onSuccess {
                            detail = null // 详情页的那台已经没了，留在页内没有意义
                            toast = Str.s(R.string.device_detail_revoked_toast)
                            reload()
                        }
                        .onFailure { toast = it.userMessage(Str.s(R.string.device_detail_revoke_failed)) }
                    revoking = ""
                }
            },
            onDismiss = { confirmOne = null },
        )
    }

    if (confirmOthers) {
        IMConfirmDialog(
            title = stringResource(R.string.device_list_revoke_all_action),
            message = stringResource(R.string.device_list_revoke_all_message),
            confirmText = stringResource(R.string.device_list_revoke_all_confirm),
            onConfirm = {
                revoking = REVOKE_ALL
                scope.launch {
                    runCatchingCancellable { client.devices.revokeOthers() }
                        .onSuccess { toast = Str.s(R.string.device_list_revoked_other_toast); reload() }
                        .onFailure { toast = it.userMessage(Str.s(R.string.device_list_revoke_other_failed)) }
                    revoking = ""
                }
            },
            onDismiss = { confirmOthers = false },
        )
    }

    toast?.let { IMToast(it) { toast = null } }
}
