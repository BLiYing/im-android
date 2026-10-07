package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AppearanceStore
import com.libeyond.imandroid.data.BackgroundDisconnect
import com.libeyond.imandroid.data.PowerSaveMode
import com.libeyond.imandroid.data.PowerSavingStore
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.PowerSavingItem
import com.libeyond.imandroid.ui.screens.PowerSavingScreen

/**
 * 省电模式页的状态桥：偏好读写全走 [PowerSavingStore]（「界面动画」写 [AppearanceStore]，与外观页同一个值）；
 * Screen 只管画。锁定行点击只弹 Toast、不改值（§2-5）。
 */
@Composable
fun PowerSavingHost(client: IMClient, onBack: () -> Unit) {
    val prefs by PowerSavingStore.state.collectAsState()
    val status by PowerSavingStore.status.collectAsState()
    val appearance by AppearanceStore.state.collectAsState()
    val fcmEnabled by PowerSavingStore.fcmEnabled.collectAsState()
    val tokenReported by client.fcmTokenStore.reportedToken.collectAsState()
    var toast by remember { mutableStateOf<String?>(null) }
    val lockedToast = stringResource(R.string.power_saving_item_locked_toast)

    // 进页刷新一次 fcm_enabled（失败保留旧值：拉不到就按「不可用」处理，不断连）
    LaunchedEffect(Unit) {
        runCatchingCancellable { client.conversationsApi.serverConfig() }
            .onSuccess { PowerSavingStore.setFcmEnabled(it.fcmEnabled) }
    }

    BackHandler(onBack = onBack)
    PowerSavingScreen(
        prefs = prefs,
        status = status,
        animationsPref = appearance.animationsEnabled,
        backgroundAvailable = BackgroundDisconnect.pushReachable(fcmEnabled, tokenReported != null),
        onMode = { m -> PowerSavingStore.update { it.copy(mode = m) } },
        onThreshold = { t -> PowerSavingStore.update { it.copy(threshold = t) } },
        onFollowSystem = { on -> PowerSavingStore.update { it.copy(followSystem = on) } },
        onItem = { item, on ->
            when (item) {
                PowerSavingItem.Animations -> {
                    AppearanceStore.update { it.copy(animationsEnabled = on) }
                    PowerSavingStore.recompute()
                }
                PowerSavingItem.AutoDownload -> PowerSavingStore.update { it.copy(autoDownload = on) }
                PowerSavingItem.VideoPreload -> PowerSavingStore.update { it.copy(videoPreload = on) }
                PowerSavingItem.BackgroundConnection -> PowerSavingStore.update { it.copy(backgroundConnection = on) }
            }
        },
        onLockedClick = { toast = lockedToast },
        onBack = onBack,
    )
    toast?.let { IMToast(it) { toast = null } }
}

/** 「我」页入口行右值：已开启（任一原因生效）/ 低于 N%（自动、未生效）/ 关闭。 */
@Composable
fun powerSavingEntryLabel(): String {
    val prefs by PowerSavingStore.state.collectAsState()
    val status by PowerSavingStore.status.collectAsState()
    return when {
        status.active -> stringResource(R.string.power_saving_row_on)
        prefs.mode == PowerSaveMode.AUTO -> stringResource(R.string.power_saving_row_below, prefs.threshold)
        else -> stringResource(R.string.common_off)
    }
}

/** §5 自动开启提示：每个放电周期一次，状态机在 [PowerSavingStore]（`PowerSavePrompt`），这里只负责弹。 */
@Composable
fun PowerSavingAutoToast() {
    val percent by PowerSavingStore.autoToast.collectAsState()
    percent?.let { p ->
        IMToast(stringResource(R.string.power_saving_auto_on_toast, p)) { PowerSavingStore.consumeAutoToast() }
    }
}
