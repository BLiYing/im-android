package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.data.CategoryRule
import com.libeyond.imandroid.data.DownloadCategory
import com.libeyond.imandroid.data.DownloadNetwork
import com.libeyond.imandroid.data.DownloadSettingsUi
import com.libeyond.imandroid.data.NetworkPolicy
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.AutoDownloadCategoryScreen
import com.libeyond.imandroid.ui.screens.AutoDownloadNetworkScreen
import com.libeyond.imandroid.ui.screens.DataStorageScreen
import kotlinx.coroutines.launch

/** 「数据和存储」里的三层页面。 */
private sealed interface StoragePage {
    data object Main : StoragePage
    data class Network(val net: DownloadNetwork) : StoragePage
    data class Category(val net: DownloadNetwork, val cat: DownloadCategory) : StoragePage
}

/**
 * 数据和存储（对齐 iOS `IMDataStorageViewController` → `IMAutoDownloadNetworkViewController`
 * → `IMAutoDownloadCategoryViewController` 三层）。
 *
 * 状态与副作用都在这里，三个 Screen 纯展示（CODING_STYLE §7②）。策略的读、存、回滚、多端同步
 * 在 [com.libeyond.imandroid.data.DownloadSettingsStore]，这里只转发。
 *
 * **不像 iOS 那样每页持一份编辑副本**：iOS 深拷贝 `_working` 是为了拖滑杆时中间值不外泄到全局；
 * 本端滑杆的拖动位置只活在 `IMStepSlider` 自己的状态里、松手才进 store，不要副本也做到了。
 */
@Composable
fun DataStorageHost(client: IMClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = client.downloadSettingsStore
    val settings = store.state.collectAsState().value.settings

    var page by remember { mutableStateOf<StoragePage>(StoragePage.Main) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    /**
     * 改一个网络的策略并保存。两条纪律：
     * ① **基于 store 的当前值算下一份**，不基于重组时捕获的 `settings`——连拨两个开关时，
     *    后一次若基于旧值算，会把前一次悄悄改回去；
     * ② 挂在 [IMClient.scope] 上而不是页面作用域：拨完开关立刻返回上一页时，
     *    页面作用域一取消 PUT 就断在半路，本地停在一个没存上、也没回滚的值。
     */
    fun update(net: DownloadNetwork, change: (NetworkPolicy) -> NetworkPolicy?) {
        client.scope.launch {
            val cur = store.current
            val next = change(DownloadSettingsUi.policyOf(cur, net)) ?: return@launch
            if (!store.save(DownloadSettingsUi.withPolicy(cur, net, next))) toast = "保存失败，请检查网络后重试"
        }
    }

    fun updateRule(net: DownloadNetwork, cat: DownloadCategory, change: (CategoryRule) -> CategoryRule?) =
        update(net) { p -> change(DownloadSettingsUi.ruleOf(p, cat))?.let { DownloadSettingsUi.withRule(p, cat, it) } }

    suspend fun measure() {
        runCatchingCancellable { StorageUsage.measure(context, client.downloads) }
            .onSuccess { cacheBytes = it }
            .onFailure { cacheBytes = null }
    }

    // 打开即拉最新（iOS 同）：别的端刚改过而推送帧恰好错过时，进来看到的也得是真值
    LaunchedEffect(Unit) { store.refresh("data_storage_open") }
    // 每回到主页重算一次用量（iOS `viewWillAppear` 同）
    LaunchedEffect(page) { if (page == StoragePage.Main) measure() }

    when (val p = page) {
        StoragePage.Main -> {
            BackHandler(onBack = onBack)
            DataStorageScreen(
                cacheBytes = cacheBytes,
                settings = settings,
                onBack = onBack,
                onStorageUsage = {
                    when {
                        clearing -> Unit
                        (cacheBytes ?: 0L) > 0 -> confirmClear = true
                        // 0 字节时 iOS 弹一个只有「取消」的框；本端一句提示，少点一下
                        else -> toast = DownloadSettingsUi.clearCacheMessage(0)
                    }
                },
                onOpenNetwork = { page = StoragePage.Network(it) },
                onReset = { confirmReset = true },
            )
        }

        is StoragePage.Network -> {
            BackHandler { page = StoragePage.Main }
            AutoDownloadNetworkScreen(
                network = p.net,
                policy = DownloadSettingsUi.policyOf(settings, p.net),
                onBack = { page = StoragePage.Main },
                onEnabledChange = { on -> update(p.net) { it.copy(enabled = on) } },
                onCommitTier = { i -> update(p.net) { DownloadSettingsUi.commitTier(it, i) } },
                onOpenCategory = { page = StoragePage.Category(p.net, it) },
            )
        }

        is StoragePage.Category -> {
            BackHandler { page = StoragePage.Network(p.net) }
            AutoDownloadCategoryScreen(
                category = p.cat,
                rule = DownloadSettingsUi.ruleOf(DownloadSettingsUi.policyOf(settings, p.net), p.cat),
                onBack = { page = StoragePage.Network(p.net) },
                onSingleChange = { on -> updateRule(p.net, p.cat) { it.copy(single = on) } },
                onGroupChange = { on -> updateRule(p.net, p.cat) { it.copy(group = on) } },
                onCommitSize = { i -> updateRule(p.net, p.cat) { DownloadSettingsUi.commitSize(it, i) } },
            )
        }
    }

    if (confirmClear) {
        IMConfirmDialog(
            title = "清除缓存",
            message = DownloadSettingsUi.clearCacheMessage(cacheBytes ?: 0L),
            confirmText = "清除",
            onConfirm = {
                clearing = true
                // 与保存同理挂在 client.scope：点完确认立刻返回的话，页面作用域一取消，
                // 会停在「媒体清了、图片缓存没清」的半截状态
                client.scope.launch {
                    runCatchingCancellable { StorageUsage.clear(context, client.downloads) }
                        .onFailure { toast = it.userMessage("清除缓存失败") }
                    measure()
                    clearing = false
                }
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (confirmReset) {
        IMConfirmDialog(
            title = "重置自动下载设置",
            message = "恢复为出厂默认（移动数据中档、Wi-Fi 高档）。",
            confirmText = "重置",
            onConfirm = {
                client.scope.launch { if (!store.resetToDefaults()) toast = "重置失败，请检查网络后重试" }
            },
            onDismiss = { confirmReset = false },
        )
    }

    toast?.let { IMToast(it) { toast = null } }
}
