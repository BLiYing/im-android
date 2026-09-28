package com.libeyond.imandroid.ui.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.Waveform
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.passThroughTap
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.voice.VoicePlayback
import com.libeyond.imandroid.voice.VoicePlayer
import com.libeyond.imandroid.voice.VoiceRules
import kotlin.math.roundToInt

/**
 * 全 App 共用的语音播放器（`IMClient.voice`），由 AppRoot 提供。没提供（@Preview / 测试）时语音只画不响。
 */
val LocalVoicePlayer = staticCompositionLocalOf<VoicePlayer?> { null }

/** 语音转文字，由 AppRoot 提供（同 [LocalVoicePlayer]）。没提供（@Preview / 测试）时长按菜单不出该项。 */
val LocalVoiceTranscriber = staticCompositionLocalOf<com.libeyond.imandroid.voice.VoiceTranscriber?> { null }

/** 一条语音的播放入口参数——聊天气泡、资料页、收藏、合并转发记录四处的公约数。 */
data class VoiceSource(
    /** 播放器里的标识（[VoiceRules.playableId]；记录页 / 收藏没有消息 id 时由调用方给稳定值）。 */
    val id: String,
    val convId: String,
    /** 媒体相对地址（与下载缓存同一个键）。 */
    val url: String,
    val durationMs: Long,
    val waveform: String?,
)

/** 播放器此刻是不是在放 / 暂停在 [id] 这条；是就回那份状态。 */
@Composable
private fun playbackOf(player: VoicePlayer?, src: VoiceSource): VoicePlayback? {
    val st = player?.state?.collectAsState()?.value ?: return null
    // 标识只在会话内唯一（conv_seq），必须连会话一起比
    return st.takeIf { it.id == src.id && it.convId == src.convId }
}

/**
 * 波形（iOS `IMWaveformView`）：柱宽 3 / 间距 2.5 / 圆角 1.5 / 最低 3、上下各留 1。
 * 柱子数按**可绘制宽度**算（`waveform` 是数据、柱子数是渲染细节，设计 §1），桶内取最大值保峰形。
 * 已播放段按柱中心是否落在进度线左侧着色（与 iOS 同判据）。
 */
@Composable
internal fun WaveformBars(
    waveform: String?,
    progress: Float,
    active: Color,
    inactive: Color,
    modifier: Modifier = Modifier,
) {
    val amps = remember(waveform) { Waveform.amplitudes(waveform) }
    Canvas(modifier) {
        val bw = 3.dp.toPx()
        val gap = 2.5.dp.toPx()
        val pitch = bw + gap
        if (size.width < 4f || size.height < 4f) return@Canvas
        val n = maxOf(1, ((size.width + gap) / pitch).toInt())
        val bars = Waveform.bars(amps, n)
        val minH = 3.dp.toPx()
        val maxH = size.height - 2.dp.toPx()
        val px = progress.coerceIn(0f, 1f) * size.width
        val r = CornerRadius(1.5.dp.toPx())
        for (i in 0 until n) {
            val h = maxOf(minH, bars[i] * maxH)
            val x = i * pitch
            drawRoundRect(
                color = if (x + bw / 2 < px) active else inactive,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(bw, h),
                cornerRadius = r,
            )
        }
    }
}

/** 34dp 圆形播放键；播放中带 2s 呼吸光晕（设计 §6.1 tokens），暂停即停。 */
@Composable
private fun VoicePlayButton(playing: Boolean, background: Color, onTap: () -> Unit) {
    val c = IMTheme.colors
    val label = stringResource(if (playing) R.string.common_pause else R.string.common_play)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
        if (playing) {
            val t = rememberInfiniteTransition(label = "voice-halo")
            val k by t.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
                label = "voice-halo-k",
            )
            Box(
                Modifier.size(34.dp).scale(1f + 0.35f * k).clip(CircleShape)
                    .background(background.copy(alpha = 0.35f * (1f - k))),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).clip(CircleShape).background(background)
                .semantics { contentDescription = label }
                .passThroughTap(onTap = onTap),
        ) {
            Image(
                if (playing) Lucide.Pause else Lucide.Play, null, Modifier.size(15.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
    }
}

/**
 * 点播放 / 暂停。成为当前条即记「已播放」（设计 §7：判据是「点了」不是「听完了」），
 * 手动点与接力自动切换走同一条路径——所以标记放在订阅处，不放在点击处。
 */
@Composable
private fun MarkPlayedWhenCurrent(player: VoicePlayer?, src: VoiceSource, current: Boolean) {
    LaunchedEffect(current, src.id) {
        if (current && player != null) player.markPlayed(src.convId, src.id)
    }
}

/** 拖拽 scrub 的浮签："0:07 / 0:12"，深底白字，浮在波形上方。 */
@Composable
private fun ScrubTip(text: String) {
    val c = IMTheme.colors
    Text(
        text, color = c.onMedia, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.overlayStrong)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 聊天气泡里的语音（设计 §6.1 + 2026-08-27 拍板的现行布局，iOS `IMVoiceBubbleCell` / Web `VoiceBubble` 同构）：
 * `[▶]  波形 / 时长(播放中=剩余) … [倍速]`，消息时间行由外层气泡画在最下面靠右；未播红点在右上角。
 * 宽度 [VoiceRules.bubbleWidthDp]（含气泡内边距，所以这里减掉两侧 [horizontalInset]）。
 */
@Composable
internal fun VoiceBubbleBody(src: VoiceSource, mine: Boolean, horizontalInset: Dp) {
    val c = IMTheme.colors
    val player = LocalVoicePlayer.current
    val pb = playbackOf(player, src)
    val active = pb != null
    val version = player?.played?.version?.collectAsState()?.value
    val played = remember(version, src.id) { player?.hasPlayed(src.convId, src.id) ?: true }
    var toast by remember { mutableStateOf<String?>(null) }
    toast?.let { IMToast(it) { toast = null } }
    MarkPlayedWhenCurrent(player, src, active)
    val toggle = { player?.toggle(src.id, src.convId, src.url, relayable = true, durationHintMs = src.durationMs) { toast = it } }

    val width = (VoiceRules.bubbleWidthDp(src.durationMs).dp - horizontalInset * 2).coerceAtLeast(120.dp)
    Box(Modifier.width(width).passThroughTap { toggle() }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            VoicePlayButton(pb?.playing == true, if (mine) c.voicePlayMine else c.accent) { toggle() }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                ScrubbableWave(src, pb, player, mine)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        MediaUrl.formatDuration(VoiceRules.shownMillis(src.durationMs, pb?.progress ?: 0f, active).toInt()),
                        color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    // 倍速胶囊：**只在这条播放 / 暂停中出现**（静息态气泡只有三件事，设计 §0.1），会话级记忆
                    if (active && player != null) SpeedPill(player, src.convId, mine)
                }
            }
        }
        // 未播红点：右上角（设计 §7「红点位置两端一致」），只给对方的、本机没点过的
        if (!mine && !played && !active) {
            val unplayed = stringResource(R.string.chat_voice_unplayed)
            Box(
                Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(c.danger)
                    .semantics { contentDescription = unplayed },
            )
        }
    }
}

/** 波形 + 拖拽 scrub：只有正在播 / 暂停的那条可拖（与 iOS `gestureRecognizerShouldBegin:` 同判据）。 */
@Composable
private fun ScrubbableWave(src: VoiceSource, pb: VoicePlayback?, player: VoicePlayer?, mine: Boolean) {
    val c = IMTheme.colors
    val density = LocalDensity.current
    var scrubX by remember { mutableFloatStateOf(-1f) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val dragging = scrubX >= 0f && pb != null
        val progress = if (dragging && widthPx > 0) scrubX / widthPx else pb?.progress ?: 0f
        WaveformBars(
            src.waveform, progress,
            active = if (mine) c.textPrimary else c.accent,
            inactive = if (mine) c.voiceWaveInactiveMine else c.voiceWaveInactive,
            modifier = Modifier.fillMaxWidth().height(24.dp).pointerInput(src.id, pb != null) {
                if (pb == null || player == null) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { scrubX = it.x.coerceIn(0f, size.width.toFloat()) },
                    onDragEnd = { scrubX = -1f },
                    onDragCancel = { scrubX = -1f },
                ) { change, _ ->
                    change.consume()
                    scrubX = change.position.x.coerceIn(0f, size.width.toFloat())
                    player.seek(src.id, scrubX / size.width)
                }
            },
        )
        if (dragging) {
            val cur = (progress * src.durationMs).toLong()
            val tip = "${MediaUrl.formatDuration(cur.toInt())} / ${MediaUrl.formatDuration(src.durationMs.toInt())}"
            // 浮签中心跟着手指、底边在波形上方（iOS `scrubTip` 同位）
            Box(Modifier.offset { IntOffset((scrubX - 30.dp.toPx()).roundToInt(), -22.dp.roundToPx()) }) { ScrubTip(tip) }
        }
    }
}

/** 倍速胶囊：`1x → 1.5x → 2x`，点按就地切换。 */
@Composable
private fun SpeedPill(player: VoicePlayer, convId: String, mine: Boolean) {
    val c = IMTheme.colors
    var rate by remember(convId) { mutableFloatStateOf(player.rates.rateFor(convId)) }
    val tint = if (mine) c.textPrimary else c.accent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(16.dp).widthIn(min = 28.dp).clip(RoundedCornerShape(8.dp))
            .background(if (mine) c.voiceSpeedBgMine else c.voiceSpeedBg)
            .passThroughTap {
                rate = VoiceRules.nextRate(rate)
                player.setRate(convId, rate)
            }
            .padding(horizontal = 6.dp),
    ) {
        Text(VoiceRules.rateLabel(rate), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * 迷你播放器（iOS `IMVoiceMiniPlayerView` / Web `VoiceBubble variant="mini"`）：资料页语音页签、收藏、
 * 合并转发记录三处复用。`▶ + vertical(波形 / 时长)`，波形可点即播；meta 只显时长（播放中=剩余）——
 * 外层行本就有独立的时间行，重复即噪音（2026-08-27 拍板）。播完即停、不参与接力。
 */
@Composable
internal fun VoiceMiniPlayer(src: VoiceSource, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val player = LocalVoicePlayer.current
    val pb = playbackOf(player, src)
    var toast by remember { mutableStateOf<String?>(null) }
    toast?.let { IMToast(it) { toast = null } }
    MarkPlayedWhenCurrent(player, src, pb != null)
    val toggle = { player?.toggle(src.id, src.convId, src.url, relayable = false, durationHintMs = src.durationMs) { toast = it } }
    Row(modifier.passThroughTap { toggle() }, verticalAlignment = Alignment.CenterVertically) {
        VoicePlayButton(pb?.playing == true, c.accent) { toggle() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            WaveformBars(
                src.waveform, pb?.progress ?: 0f,
                active = c.accent, inactive = c.voiceWaveInactive,
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                MediaUrl.formatDuration(VoiceRules.shownMillis(src.durationMs, pb?.progress ?: 0f, pb != null).toInt()),
                color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * 语音转写面板（服务端识别，`VOICE_TRANSCRIBE_DESIGN.md`）：左侧引用线 + 文本 + 尾行隐私说明，
 * 与 iOS `IMVoiceBubbleCell` 的转写面板、Web `.voice-transcript` 同一视觉语系（左侧竖线借用
 * 既有 [com.libeyond.imandroid.ui.screens.QuoteBlock] 的 `IntrinsicSize.Min` 撑满高度写法）。
 * 挂在气泡**外面**（同一条消息列，气泡下方），不是气泡内的一部分——同 Web `msg-item` 的结构。
 */
@Composable
internal fun VoiceTranscriptPanel(
    transcript: com.libeyond.imandroid.voice.VoiceTranscript,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    Row(modifier = modifier.padding(top = 6.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(c.accent))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = when (transcript) {
                    is com.libeyond.imandroid.voice.VoiceTranscript.Loading -> stringResource(R.string.chat_voice_transcribing)
                    is com.libeyond.imandroid.voice.VoiceTranscript.Done -> transcript.text
                },
                color = c.textPrimary, fontSize = 14.sp,
            )
            if (transcript is com.libeyond.imandroid.voice.VoiceTranscript.Done) {
                Text(stringResource(R.string.chat_voice_transcript_note), color = c.textSecondary, fontSize = 10.sp)
            }
        }
    }
}
