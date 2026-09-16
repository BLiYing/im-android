package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.VideoTap
import com.libeyond.imandroid.data.VideoTapAction
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.delay

/** 中央大播放钮直径（iOS `_playButton` 72pt）。 */
private val CENTER_PLAY = 72.dp

/** 倍速档位（iOS `_speeds`：1.0 / 1.5 / 2.0，点一下换一档）。 */
private val SPEEDS = floatArrayOf(1.0f, 1.5f, 2.0f)

/**
 * 视频播放（Media3 / ExoPlayer）。
 *
 * ### 与 iOS 对齐的几条
 * 1. **封面先显、点了才播**（iOS `IMMediaViewerViewController` 的 `_started` 门控，
 *    CLIENT_PARITY 任务3 记作「封面待点不自动播」）。进来就自动播会在群里误触时
 *    突然外放出声，也白白吃掉流量。
 * 2. **点画面就播放 / 暂停**（iOS `togglePlayback` 挂在整个视频容器上）：此前本端只有中间那枚钮能点，
 *    点画面没反应（2026-09-16 用户报）。判据在 [VideoTap]。
 * 3. **底部一行：时间 · 进度条 · 倍速**，贴在查看器右下角按钮排**之上**（[controlsBottomPadding]），
 *    此前画在屏幕最底、与按钮排叠在一起（同日用户报）。
 * 4. **离开就停**：`DisposableEffect` 释放 player，并跟随生命周期在 onStop 暂停——
 *    iOS 是 `viewDidDisappear` 里 `[_player pause]`。不做的话切到后台还在放声音。
 * 5. **播完回到开头、停在暂停态**（iOS `videoDidEnd`）：ExoPlayer 播到头停在 ENDED，
 *    这时再 `play()` 什么都不会发生——不回到开头的话，播完点播放钮没有反应。
 *
 * ### 控件为什么自绘
 * `PlayerView` 自带的控制条有自己一套 Material 配色，与本端令牌（`IMTheme`）对不上。
 * 所以 `useController = false`，只借它的 Surface 与 `resizeMode`（比例处理是真正难自己写对的那部分）。
 * iOS 侧同样是手搓 `_playButton` / `_scrubber` / `_timeLabel`。
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
    /** 底部那一行离屏幕底（导航栏之上）多高：要让出查看器右下角那排按钮（见 [VIDEO_BAR_BOTTOM]）。 */
    controlsBottomPadding: Dp = 0.dp,
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
    var speedIdx by remember(absolute) { mutableIntStateOf(0) }
    /** 没换过档时显示「倍速」二字，换过才显示「1.5x」（iOS 同）。 */
    var speedTouched by remember(absolute) { mutableStateOf(false) }

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
                if (state == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
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

    // 画面与中央钮共用这一个动作：开播 / 暂停 / 继续
    val toggle: () -> Unit = {
        when (VideoTap.actionFor(started, player.playWhenReady)) {
            VideoTapAction.Start -> {
                started = true
                player.play()
            }
            VideoTapAction.Pause -> player.pause()
            VideoTapAction.Resume -> player.play()
        }
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

        // 点画面的那一层。**盖一层透明的而不是挂在 PlayerView 上**：触摸先进 AndroidView 里的原生 View，
        // 它吃不吃这一下由 View 自己说了算，Compose 修饰符挂在它外面收不收得到没有保证。
        // 放在封面之上、控件之下：中央钮、进度条、查看器的按钮排都在它上面，照常优先
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    enabled = !failed,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = toggle,
                ),
        )

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
            // 居中大播放钮（iOS 同）。播放中不显，让画面干净；暂停靠点画面
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(CENTER_PLAY)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable(onClick = toggle),
                contentAlignment = Alignment.Center,
            ) {
                // **不用 "▶" 这类文字字形**：部分设备（实测 OPPO ColorOS）会用彩色 emoji 字体渲染，看着像坏了
                Image(
                    imageVector = Lucide.Play,
                    contentDescription = "播放",
                    modifier = Modifier.size(32.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
        }

        if (!failed) {
            PlaybackBar(
                positionMs = if (scrubbing) scrubTo.toLong() else positionMs,
                durationMs = durationMs,
                speedLabel = if (speedTouched) String.format(java.util.Locale.US, "%.1fx", SPEEDS[speedIdx]) else "倍速",
                onScrubStart = { scrubbing = true },
                onScrub = { scrubTo = it },
                onScrubEnd = {
                    // 没开播就拖：收起封面、停在拖到的位置，不自动播（iOS `scrubberEnded:` 同）
                    started = true
                    player.seekTo(scrubTo.toLong())
                    scrubbing = false
                },
                onCycleSpeed = {
                    speedIdx = (speedIdx + 1) % SPEEDS.size
                    speedTouched = true
                    player.setPlaybackSpeed(SPEEDS[speedIdx])
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = controlsBottomPadding),
            )
        }
    }
}

/** 底部一行：`00:00 / 01:23` · 进度条 · 倍速（iOS `setupVideoBottomRowIfNeeded:`，无底色直接压在画面上）。 */
@Composable
private fun PlaybackBar(
    positionMs: Long,
    durationMs: Long,
    speedLabel: String,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 等宽数字：秒数跳动时整行不左右抖
        Text(
            "${clock(positionMs)} / ${clock(durationMs)}",
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(12.dp))
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
        Spacer(Modifier.width(16.dp))
        Text(
            speedLabel,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onCycleSpeed).padding(vertical = 6.dp),
        )
    }
}

/** `mm:ss`（iOS `mmss:` 同口径：分钟也补两位）。 */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${(total / 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
}

/** 播放器容器的圆角（查看器里不裁角，这里留给将来内联播放用）。 */
internal val PlayerCorner = RoundedCornerShape(0.dp)
