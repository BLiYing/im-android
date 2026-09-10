package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.RotateCw
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.data.DownloadSettings
import com.libeyond.imandroid.data.DownloadState
import com.libeyond.imandroid.data.DownloadTap
import com.libeyond.imandroid.data.MediaDownloader
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 下载门控要用的东西——放 CompositionLocal 而不是层层传参：媒体渲染点有四处
 * （气泡 / 宫格逐格 / 文件气泡 / 详情页宫格），每处都在很深的地方，
 * 一路传下去会把十几个组件的签名都染上。
 */
class MediaGateEnv(
    val downloads: MediaDownloader,
    val settings: () -> DownloadSettings,
    val onWifi: Boolean,
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

    // 策略放行就自动开下。**每条只判一次**（key 用 url）——不然每次重组都会再调一次 start，
    // start 内部虽然幂等，但每帧调一次是纯浪费。
    LaunchedEffect(url, isGroup, autoPrefetch) {
        if (url.isBlank() || !autoPrefetch) return@LaunchedEffect
        val auto = DownloadPolicy.shouldAutoDownload(
            env.settings(), contentType, sizeBytes, isGroup, env.onWifi,
        )
        if (auto) env.downloads.start(url, isVideo, sizeBytes)
    }

    val local = if (state.phase == DownloadPhase.Ready) env.downloads.localFile(url, isVideo) else null
    return GateInfo(state, local, localFile = local, onTap = {
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

/**
 * 门控徽标：**未下载 ↓+大小 / 下载中 环+⏸ / 暂停 ↓ / 失败 ↻ / 失效 ⊘**。
 * 五态各画各的（对齐 iOS `IMDownloadProgress`），合并任意两个都会让用户误解当前发生了什么。
 *
 * 就绪态**不画**——画一个"已下载"的标记只是噪声。
 */
@Composable
fun DownloadBadge(
    state: DownloadState,
    sizeBytes: Long,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** 小格子（宫格）里徽标要缩小，且不画大小文字。 */
    compact: Boolean = false,
) {
    if (state.phase == DownloadPhase.Ready) return
    val c = IMTheme.colors
    val side: Dp = if (compact) 32.dp else 44.dp
    val icon: Dp = if (compact) 14.dp else 20.dp

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(side).clip(CircleShape).background(c.overlayStrong)
                    // **失效不给点**：给了也是每点一次拉一次 404。
                    // 不用 clickable：它吃掉 down，徽标盖着的气泡/格子就长按不出菜单了（#13）
                    .passThroughTap(enabled = state.phase != DownloadPhase.Expired, onTap = onTap),
                contentAlignment = Alignment.Center,
            ) {
                when (state.phase) {
                    DownloadPhase.Downloading -> {
                        if (state.hasPercent) {
                            CircularProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier.size(side),
                                color = c.onMedia,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            // 服务端没给 Content-Length：只能显示"在动"
                            CircularProgressIndicator(
                                modifier = Modifier.size(side),
                                color = c.onMedia,
                                strokeWidth = 2.dp,
                            )
                        }
                        Image(Lucide.Pause, "暂停", Modifier.size(icon), colorFilter = ColorFilter.tint(c.onMedia))
                    }
                    DownloadPhase.Failed -> Image(
                        Lucide.RotateCw, "重试", Modifier.size(icon), colorFilter = ColorFilter.tint(c.onMedia),
                    )
                    DownloadPhase.Expired -> Image(
                        Lucide.Ban, "已失效", Modifier.size(icon), colorFilter = ColorFilter.tint(c.onMedia),
                    )
                    else -> Image(
                        Lucide.Download, "下载", Modifier.size(icon), colorFilter = ColorFilter.tint(c.onMedia),
                    )
                }
            }
            val label = when {
                state.phase == DownloadPhase.Expired -> "文件已失效"
                compact -> ""
                state.phase == DownloadPhase.Downloading -> ""
                sizeBytes > 0 -> MediaUrl.formatSize(sizeBytes)
                else -> ""
            }
            if (label.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(c.overlayStrong)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(label, color = c.onMedia, fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
