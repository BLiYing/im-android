package com.libeyond.imandroid

import android.app.Application
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.NetworkMonitor
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.logging.RemoteLogSink
import com.libeyond.imandroid.sdk.session.DeviceIdentity

class IMApp : Application(), coil.ImageLoaderFactory {

    /** 全应用唯一的 SDK 门面。**不用 DI 框架**：当前只有一个单例，引 Hilt 是负收益。 */
    lateinit var client: IMClient
        private set

    private lateinit var network: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        client = IMClient(this)
        // 开发期日志回传到 IMServer 的 /__devlog（与 iOS / Web 同一条通道）。**仅 Debug 构建**；
        // 服务端没开 -dev-logsink 时路由不存在，请求失败就丢，不影响任何功能。
        if (BuildConfig.DEBUG) {
            IMLog.addSink(
                RemoteLogSink(
                    host = { client.host },
                    useTls = BuildConfig.USE_TLS,
                    deviceTag = DeviceIdentity(this).deviceId.take(8),
                ),
            )
        }
        network = NetworkMonitor(this) { client.wake("network_available") }
        network.start()
        IMLog.tag("IM.App").i("app_start", "versionName" to BuildConfig.VERSION_NAME)
    }

    /**
     * 全局 Coil loader 注册 `VideoFrameDecoder`。
     *
     * **为什么必须有**：聊天里的视频待发气泡显示的是**本地 `content://` URI**，
     * 没有这个解码器 Coil 只会得到一片空白（2026-09-07 真机实测：发失败的视频气泡是全黑的）。
     * 服务端回来的视频有 `poster` 封面图、不需要它，但本地那一段没有封面可用。
     *
     * :media-picker 模块自己持一个私有 loader 也注册了它——**那是刻意的重复**：
     * 模块往宿主的全局 loader 上偷偷塞 decoder，换个 App 接进来就会莫名其妙。
     */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(coil.decode.VideoFrameDecoder.Factory()) }
            .build()
}
