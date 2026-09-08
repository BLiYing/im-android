package com.libeyond.imandroid.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 当前是不是 Wi-Fi（含以太网）。自动下载策略要按它选 `wifi` / `cellular` 档。
 *
 * **本端能区分网络类型，这是相对 Web 的一个真实优势**——Web 恒用 Wi-Fi 档
 * （浏览器分不清），所以在移动数据下会按 Wi-Fi 的宽松阈值自动拉视频。
 * 别照抄那个限制。
 *
 * 拿不到（权限被拒 / 系统异常）**按 Wi-Fi 处理**：这台设备多半就是在 Wi-Fi 上，
 * 按移动数据处理会让用户在家里也要为每条视频点一次 ↓。
 */
@Composable
fun rememberOnWifi(): Boolean {
    val context = LocalContext.current
    var onWifi by remember { mutableStateOf(isOnWifi(context)) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, c: NetworkCapabilities) {
                onWifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            override fun onLost(n: android.net.Network) { onWifi = isOnWifi(context) }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return onWifi
}

private fun isOnWifi(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}.getOrDefault(true)
