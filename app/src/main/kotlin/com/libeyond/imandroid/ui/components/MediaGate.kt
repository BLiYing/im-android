package com.libeyond.imandroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.data.DownloadSettings
import com.libeyond.imandroid.data.DownloadState
import com.libeyond.imandroid.data.DownloadTap
import com.libeyond.imandroid.data.MediaDownloader

/**
 * 下载门控要用的东西——放 CompositionLocal 而不是层层传参：媒体渲染点有四处
 * （气泡 / 宫格逐格 / 文件气泡 / 详情页宫格），每处都在很深的地方，
 * 一路传下去会把十几个组件的签名都染上。
 */
class MediaGateEnv(
    val downloads: MediaDownloader,
    val settings: () -> DownloadSettings,
    val onWifi: Boolean,
    /**
     * 我是谁（iOS 编排器构造时就带 `myUserID`）。**归档与收藏宫格判"自己发的不门控"要用**
     * （[DownloadPolicy.archiveTileUngated]）：那两处拿到的是服务端来的条目，没有聊天气泡那样现成的 `mine`。
     * 用函数而不是值：切账号不重建这份环境。
     */
    val myUid: () -> String = { "" },
)

val LocalMediaGate = compositionLocalOf<MediaGateEnv?> { null }

/** 一处媒体的门控结论。 */
data class GateInfo(
    val state: DownloadState,
    /** 喂给 Coil 的东西：就绪 → 本地文件；否则 null（**不要给远端 URL**，那就绕过门控了）。 */
    val model: Any?,
    val onTap: () -> Unit,
    /** 已下载到本地的原件；未就绪为 null。查看器/播放器/存相册都该优先用它。 */
    val localFile: java.io.File? = null,
    /**
     * 自动下载策略此刻放不放行这一条（[DownloadPolicy.shouldAutoDownload]）。
     * **与是否真的去下无关**——`autoPrefetch = false` 的归档页照样算它：图片放行即按地址显示
     * （见 [DownloadPolicy.archiveTileUngated]）。
     */
    val autoAllowed: Boolean = true,
) {
    val ready: Boolean get() = state.phase == DownloadPhase.Ready
}

/**
 * 算出这条媒体此刻的门控状态，并在策略放行时**自动开下**。
 *
 * @param url 媒体的**相对**地址（换 host 不该让缓存失效）
 * @param sizeBytes 服务端给的字节数；`0` = 未知（策略据此保守判否）
 */
@Composable
fun rememberGate(
    url: String,
    contentType: String,
    sizeBytes: Long,
    isGroup: Boolean,
    /**
     * 策略放行时是否**自动开下**。
     *
     * **详情页归档与会话媒体库必须传 false**（对齐 iOS `IMChatDetailViewController`
     * 与 `IMConversationMediaViewController` 的 `autoPrefetchEnabled = NO`）：
     * 那两处是"翻历史"，一屏能列出几十条媒体，自动下会在用户只想看一眼列表时
     * 静默拉走几百 MB。它们只**反映**状态，下不下由用户点。
     */
    autoPrefetch: Boolean = true,
): GateInfo {
    val env = LocalMediaGate.current
    val isVideo = contentType == "video"

    // 没装门控（预览/测试）→ 直接放行走远端地址，与接门控之前的行为一致
    if (env == null) {
        return GateInfo(DownloadState(DownloadPhase.Ready), url.ifBlank { null }, onTap = {})
    }

    val states by env.downloads.states.collectAsState()
    val state = remember2(states, url) { env.downloads.stateOf(url, isVideo) }

    // 策略判一次就记住：宫格滚动时每格每次重组都会走到这里。策略本身也进 key——
    // 进主界面后才拉到的账号策略（或 capabilities_update 推来的新策略）要能改过来
    val settings = env.settings()
    val auto = androidx.compose.runtime.remember(url, contentType, sizeBytes, isGroup, env.onWifi, settings) {
        DownloadPolicy.shouldAutoDownload(settings, contentType, sizeBytes, isGroup, env.onWifi)
    }

    // 策略放行就自动开下。**每条只判一次**（key 用 url）——不然每次重组都会再调一次 start，
    // start 内部虽然幂等，但每帧调一次是纯浪费。
    LaunchedEffect(url, isGroup, autoPrefetch) {
        if (url.isBlank() || !autoPrefetch) return@LaunchedEffect
        if (auto) env.downloads.start(url, isVideo, sizeBytes)
    }

    val local = if (state.phase == DownloadPhase.Ready) env.downloads.localFile(url, isVideo) else null
    return GateInfo(state, local, localFile = local, autoAllowed = auto, onTap = {
        when (state.tapAction()) {
            DownloadTap.Start -> env.downloads.start(url, isVideo, sizeBytes)
            DownloadTap.Pause -> env.downloads.pause(url, isVideo)
            DownloadTap.Open, DownloadTap.None -> Unit
        }
    })
}

/** `remember` 的一层薄封装，只为让上面那行读起来是「按 states 与 url 重算」。 */
@Composable
private fun <T> remember2(a: Any?, b: Any?, calc: () -> T): T =
    androidx.compose.runtime.remember(a, b) { calc() }
