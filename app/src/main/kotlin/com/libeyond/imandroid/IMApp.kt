package com.libeyond.imandroid

import android.app.Application
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.NetworkMonitor
import com.libeyond.imandroid.sdk.logging.IMLog

class IMApp : Application() {

    /** 全应用唯一的 SDK 门面。**不用 DI 框架**：当前只有一个单例，引 Hilt 是负收益。 */
    lateinit var client: IMClient
        private set

    private lateinit var network: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        client = IMClient(this)
        network = NetworkMonitor(this) { client.wake("network_available") }
        network.start()
        IMLog.tag("IM.App").i("app_start", "versionName" to BuildConfig.VERSION_NAME)
    }
}
