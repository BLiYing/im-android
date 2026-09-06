package com.libeyond.imandroid.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 网络可达跃迁 → 唤醒连接（`CLIENT_PARITY.md`「网络恢复秒连」行，iOS/Web 已有）。
 *
 * 只发信号，**不做判定**——该不该连由 [com.libeyond.imandroid.sdk.ws.wakeActionFor] 决定。
 * 把判定放这里的话，iOS/Web/Android 三处各写一遍必然漂移。
 */
class NetworkMonitor(context: Context, private val onAvailable: () -> Unit) {

    private val log = IMLog.tag("IM.Net")
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            log.i("network_available")
            onAvailable()
        }
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm?.registerNetworkCallback(req, callback)
        } catch (e: SecurityException) {
            // 缺 ACCESS_NETWORK_STATE 时会抛。不致命——退化成"只靠回前台唤醒"。
            log.w("network_callback_register_failed", "err" to e.javaClass.simpleName)
        }
    }

    fun stop() {
        try { cm?.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) {}
    }
}
