package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.delay

/**
 * 视频播放（Media3 / ExoPlayer）。
 *
 * ### 与 iOS 对齐的两条
 * 1. **封面先显、点了才播**（iOS `IMMediaViewerViewController` 的 `_started` 门控，
 *    CLIENT_PARITY 任务3 记作「封面待点不自动播」）。进来就自动播会在群里误触时
 *    突然外放出声，也白白吃掉流量。
 * 2. **离开就停**：`DisposableEffect` 释放 player，并跟随生命周期在 onStop 暂停——
 *    iOS 是 `viewDidDisappear` 里 `[_player pause]`。不做的话切到后台还在放声音。
 *
 * ### 控件为什么自绘
 * `PlayerView` 自带的控制条有自己一套 Material 配色，与本端令牌（`IMTheme`）对不上，
 * 且不跟随「聊天主题色」。所以 `useController = false`，只借它的 Surface 与
 * `resizeMode`（比例处理是真正难自己写对的那部分）。iOS 侧同样是手搓
 * `_playButton` / `_scrubber` / `_timeLabel`。
 */
@Composable
internal fun VideoPlayer(
    /** 已下载到本地的原件；有就放它，没有才流式拉远端。 */
    localFile: java.io.File? = null,
    /** 远端 URL 或本地 `content://`（待发/失败的那条也能点开看）。 */
    url: String,
    posterUrl: String?,
    host: String,
    useTls: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    val context = LocalContext.current
    val log = remember { com.libeyond.imandroid.sdk.logging.IMLog.tag("IM.Video") }
    // **已下载的原件优先**（对齐 iOS：策略放行 → 整段预取 → 查看器放本地）。
    // 不用它的话，用户点 ↓ 下完 10MB，点开播放又从网络重新流一遍——门控白做了。
    // 没有本地文件才回落流式（归档那条路可以打开一条没下过的视频，iOS 那侧同样是流式 + 「查看原视频」）。
    val absolute = remember(url, host, useTls, localFile) {
        localFile?.let { android.net.Uri.fromFile(it).toString() }
            ?: MediaUrl.absolute(url, host, useTls)
    }

    val player = remember(absolute) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(absolute))
            prepare()
            playWhenReady = false // 封面待点不自动播
        }
    }
    var started by remember(absolute) { mutableStateOf(false) }
    var playing by remember(absolute) { mutableStateOf(false) }
    var positionMs by remember(absolute) { mutableLongStateOf(0L) }
    var durationMs by remember(absolute) { mutableLongStateOf(0L) }
    /** 拖动中：这期间不要用播放位置去覆盖滑块，否则手指会被自己拽回去。 */
    var scrubbing by remember(absolute) { mutableStateOf(false) }
    var scrubTo by remember(absolute) { mutableFloatStateOf(0f) }
    var failed by remember(absolute) { mutableStateOf(false) }
    /**
     * 正在缓冲。**必须与「已暂停」分开**：不分开的话缓冲期间会重新显示大播放钮，
     * 看着像刚才那一下没点上，用户再点一次——而那一下真的会把它暂停。
     * （2026-09-08 真机撞见：点播放后 3 秒仍是播放钮 + `0:00/0:00`。）
     */
    var buffering by remember(absolute) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    // duration 在 READY 之前是 C.TIME_UNSET，这里补一次，
                    // 免得进度条右侧一直停在 0:00
                    durationMs = player.duration.coerceAtLeast(0)
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                log.w("video_error", "code" to error.errorCodeName)
                // 解不了的编码（本端不转码，HEVC 在部分设备上放不了）、文件已被清理都会到这
                failed = true
                playing = false
            }
        }
        // **挂监听前先读一次当前状态**：`prepare()` 是在组合期（remember 里）调的，
        // IDLE→BUFFERING→READY 可能在监听器挂上之前就已经走完或走到一半，
        // 那些回调**收不到**。不补这一次的话，冷缓存下 `buffering` 一直是 false，
        // 缓冲期间显示的是大播放钮——看着像刚才那下没点上，再点一次反而暂停。
        // （2026-09-08 真机撞见：点播放后 6 秒仍是播放钮 + `0:00/0:00`。）
        buffering = player.playbackState == Player.STATE_BUFFERING
        playing = player.isPlaying
        durationMs = player.duration.coerceAtLeast(0)
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 切后台暂停。**不 release**——回来还要能接着放。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 进度轮询。**只在播放中轮询**：暂停时每 200ms 唤醒一次纯属白耗电。
    LaunchedEffect(player, playing) {
        while (playing) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0)
            delay(200)
        }
        // 停下来时再补一次，免得暂停后进度条停在上一个采样点
        positionMs = player.currentPosition
        durationMs = player.duration.coerceAtLeast(0)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 未开播前盖封面：**服务端给的 poster**，没有就黑底。
        // 不用视频首帧兜底——那要先把视频下下来，正是「封面待点不自动播」要避免的。
        if (!started && !posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = MediaUrl.absolute(posterUrl, host, useTls),
                contentDescription = "视频封面",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (failed) {
            Text(
                "这个视频放不了",
                color = c.onMediaMuted,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (started && buffering) {
            // 缓冲中：转圈，不是播放钮
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).size(44.dp),
            )
        } else if (!started || !playing) {
            // 居中大播放钮（iOS 同）。播放中不显，让画面干净。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable {
                        started = true
                        player.play()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Lucide.Play,
                    contentDescription = "播放",
                    modifier = Modifier.size(28.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
        }

        if (started && !failed) {
            PlaybackBar(
                positionMs = if (scrubbing) scrubTo.toLong() else positionMs,
                durationMs = durationMs,
                playing = playing,
                onToggle = { if (playing) player.pause() else player.play() },
                onScrubStart = { scrubbing = true },
                onScrub = { scrubTo = it },
                onScrubEnd = {
                    player.seekTo(scrubTo.toLong())
                    scrubbing = false
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun PlaybackBar(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onToggle: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                // **不用 "⏸"/"▶" 这类文字字形**：部分设备（实测 OPPO ColorOS）会用彩色 emoji 字体
                // 渲染，暂停键变成一个橙色方块，看着像坏了。图标走本端已在用的 Lucide。
                Image(
                    imageVector = if (playing) Lucide.Pause else Lucide.Play,
                    contentDescription = if (playing) "暂停" else "播放",
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(clock(positionMs), color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Slider(
                // duration 未知（还没 prepare 完）时给 1，避免 valueRange 上下界相等时抛
                value = positionMs.toFloat().coerceIn(0f, maxOf(durationMs, 1L).toFloat()),
                valueRange = 0f..maxOf(durationMs, 1L).toFloat(),
                onValueChange = { onScrubStart(); onScrub(it) },
                onValueChangeFinished = onScrubEnd,
                colors = SliderDefaults.colors(
                    thumbColor = c.accent,
                    activeTrackColor = c.accent,
                    inactiveTrackColor = Color(0x55FFFFFF),
                ),
                modifier = Modifier.weight(1f).height(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(clock(durationMs), color = Color.White, fontSize = 12.sp)
        }
    }
}

/** `m:ss`（与 `MediaUrl.formatDuration` 同口径，只是入参是毫秒的 Long）。 */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** 播放器容器的圆角（查看器里不裁角，这里留给将来内联播放用）。 */
internal val PlayerCorner = RoundedCornerShape(0.dp)
