package com.libeyond.imandroid.ui.voice

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.SendHorizontal
import com.composables.icons.lucide.Trash2
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.voice.VoicePlayback
import com.libeyond.imandroid.voice.VoiceRecorder
import com.libeyond.imandroid.voice.VoiceRules
import java.io.File
import kotlinx.coroutines.launch

/**
 * 录音入口 UI（设计 §5，草图 §03/04/12/13/14）：贴着输入栏右缘的麦克风/发送键、按住手势、
 * 悬浮层（大圆钮/呼吸环/锁钮）、锁定行（删除/胶囊/暂停·继续/发送）。
 *
 * **不做成一个持状态的 controller 类**——本文件的每个 Composable 各管一段（手势/悬浮层/锁定行/事件），
 * 跨组件共享的只有 `locked` 与 `dragOffset` 两个原始状态，由 [ComposerBar] 持有并逐个传下去，
 * 与本项目其余"输入栏配套状态"（如 [com.libeyond.imandroid.ui.MentionComposerState]）同一写法。
 */

/** 录音机的进程内单例，AppRoot 里 provide（同 [LocalVoicePlayer]）。 */
val LocalVoiceRecorder = staticCompositionLocalOf<VoiceRecorder?> { null }

// 手势几何（草图 §03/04 拍板值）：锁钮中心在按下点正上方 86dp，70dp 进高亮、34dp 到位即锁。
// 这些是排版常量，不是需要跨端单测钉住的判据（判据本身在 VoiceRules.lockPhase 里），故不搬进那个文件。
private val LOCK_ABOVE = 86.dp
private val LOCK_NEAR_R = 70.dp
private val LOCK_SNAP_R = 34.dp
private val BIG_CIRCLE = 58.dp
private const val PREVIEW_ID_PREFIX = "voice-preview:"

/** 是否问过一次麦克风权限。**必须走 [PrefsVoiceKv]**——它已经包着 `"im_voice"` 那份 prefs 文件
 * （[VoicePlayedStore]/[VoiceRateStore] 也用它），另开一条裸 `SharedPreferences` 读写同一个文件
 * 是重复造轮子，还多一条没被现有单测覆盖的路径（2026-09-28 code review 抓出）。 */
private const val MIC_ASKED_KEY = "mic_asked"

/**
 * 输入栏右缘那一格：录音入口 + 按住手势。**贯穿按住→录制→（未锁定期间）的同一个 [Box]**——
 * `pointerInput` 的 key 只有 `recorder`/`convId`，不随录制中途的外层内容 morph 而被拆装重建，
 * 否则手指按下去一半，Compose 因为外层分支切换把这个节点连带手势协程一起销毁重建，
 * 正在追踪的那次拖拽会凭空断掉（详见类注释）。真正切到锁定行（外层整段换掉）发生在
 * [locked] 变 true **之后**——那之后已经不再需要这个手势节点，销毁它没问题（免提态只剩点按）。
 */
@Composable
internal fun VoiceMicOrSendButton(
    convId: String,
    showMic: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onToast: (String) -> Unit,
    onLocked: () -> Unit,
    onDrag: (Offset) -> Unit,
    rowWidthPx: () -> Float,
    /** 这一格此刻的屏幕中心（窗口坐标）——[VoiceHoldOverlay] 靠它把悬浮层锚在真实按钮位置上，
     * 不是外层那个铺满整行的容器的角（见 [ComposerBar] 里的说明）。 */
    onGlobalCenter: (Offset) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val reportCenter = Modifier.onGloballyPositioned {
        onGlobalCenter(it.positionInWindow() + Offset(it.size.width / 2f, it.size.height / 2f))
    }
    if (!showMic) {
        Box(
            modifier = modifier
                .then(reportCenter)
                .size(d.inputControl)
                .clip(CircleShape)
                .background(if (canSend) c.accent else c.neutralControl)
                .clickable(enabled = canSend) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            VoiceIcon(Lucide.SendHorizontal, stringResource(R.string.common_send), c.onAccent)
        }
        return
    }
    val recorder = LocalVoiceRecorder.current
    val context = LocalContext.current
    val micKv = remember(context) { com.libeyond.imandroid.voice.PrefsVoiceKv(context) }
    var pendingStart by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingStart) {
            if (pressed) recorder?.start(convId) else onToast(Str.s(R.string.chat_voice_mic_authorized_retry))
        } else if (!granted) {
            onToast(Str.s(R.string.chat_voice_mic_permission_denied))
        }
        pendingStart = false
    }
    Box(
        modifier = modifier
            .then(reportCenter)
            .size(d.inputControl)
            .pointerInput(recorder, convId) {
                val lockAbovePx = LOCK_ABOVE.toPx()
                val nearPx = LOCK_NEAR_R.toPx()
                val snapPx = LOCK_SNAP_R.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    var curDx = 0f
                    var curDy = 0f
                    var locked = false
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    when {
                        granted -> if (recorder?.start(convId) != true) onToast(Str.s(R.string.chat_voice_cannot_start))
                        micKv.get(MIC_ASKED_KEY) != null -> onToast(Str.s(R.string.chat_voice_mic_permission_denied))
                        else -> {
                            micKv.put(MIC_ASKED_KEY, "1")
                            pendingStart = true
                            launcher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        curDx = change.position.x - down.position.x
                        curDy = change.position.y - down.position.y
                        change.consume()
                        onDrag(Offset(curDx, curDy))
                        if (!locked && recorder?.state?.value?.phase == VoiceRecorder.Phase.Recording) {
                            val phase = VoiceRules.lockPhase(curDx, curDy, 0f, -lockAbovePx, nearPx, snapPx)
                            if (phase == VoiceRules.LockPhase.Locked) {
                                locked = true
                                onLocked()
                            }
                        }
                    }
                    pressed = false
                    onDrag(Offset.Zero)
                    if (!locked) {
                        val st = recorder?.state?.value
                        if (st != null && st.phase == VoiceRecorder.Phase.Recording) {
                            if (VoiceRules.cancelReady(curDx, rowWidthPx())) recorder.cancel() else recorder.stopAndSend()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 录制期间这一格让位给悬浮大圆钮（VoiceHoldOverlay），自己只留手势、不画图标。
        val phase = recorder?.state?.collectAsState()?.value?.phase
        if (phase == null || phase == VoiceRecorder.Phase.Idle) {
            VoiceIcon(Lucide.Mic, stringResource(R.string.chat_media_voice_hint), c.textSecondary)
        }
    }
}

/**
 * 按住态悬浮层（草图 §03）：大圆钮跟手 + 振幅呼吸环 + 小锁钮（固定在按下点正上方）。
 * 只在「录制中且未锁定」时由 [ComposerBar] 挂出来。
 *
 * **必须是 `BoxScope` 扩展、直接画在调用方那个已有的 `Box` 里**——之前套了一层
 * `Box(Modifier.size(0.dp))` 想当"零尺寸锚点"，指望子项都用负 offset 飘出去，
 * 结果 Compose 量这层 Box 时把 `(0,0)` 的约束**连带传给了子项**，子项的 `.size(58.dp)`/
 * `.size(36.dp,52.dp)` 被外层的 0 约束顶到 0×0——大圆钮、锁钮全都量出 0 大小，
 * 真机上**整个悬浮层从头到尾都没画出来过**（2026-09-28 用户报"上滑锁定看不到锁定 icon 交互"，
 * 一路 adb 摆拍到「手指按下→上滑→锁定」全程都没能截到大圆钮或锁钮，最后定位到这层 0dp 包装）。
 * 直接把两个子项摆进调用方那个本来就有真实尺寸（输入栏那一行）的 `Box`，`align(BottomEnd)`
 * 算的 space 就是那一行的真实大小，子项不再被顶成 0，位置数值（[LOCK_ABOVE] 等）不用改。
 */
@Composable
internal fun BoxScope.VoiceHoldOverlay(
    state: VoiceRecorder.State,
    dragOffset: Offset,
    /**
     * 麦克风/发送键中心相对调用方那个 `Box` 右下角的真实像素偏移（[ComposerBar] 用两点
     * `positionInWindow()` 作差实测出来的，不是硬编码 dp）。外层 `Box` 是铺满整行的容器，
     * 它的右下角在屏幕最右边缘；按钮既不贴边（`Row` 自己还有 `inputBarEdge` 内边距）也不贴底
     * （垂直居中），不加这个偏移量的话大圆钮/锁钮会明显偏右、几乎贴边（2026-09-28 用户对照
     * 真机报的）。
     */
    anchorOffsetPx: Offset = Offset.Zero,
) {
    val c = IMTheme.colors
    val density = LocalDensity.current
    val lockPhase = with(density) {
        VoiceRules.lockPhase(dragOffset.x, dragOffset.y, 0f, -LOCK_ABOVE.toPx(), LOCK_NEAR_R.toPx(), LOCK_SNAP_R.toPx())
    }
    val ringScale = 1f + minOf(0.35f, state.amplitude * 0.5f)
    val anchorXDp = with(density) { anchorOffsetPx.x.toDp() }
    val anchorYDp = with(density) { anchorOffsetPx.y.toDp() }
    // **大圆钮必须先画**：手指上滑到锁定判定区时，圆钮的跟手位置与锁钮的固定位置本就重叠
    // （两者的落点都在按下点上方 ~LOCK_ABOVE 附近）——Compose 同一 Box 里后画的盖住先画的，
    // 锁钮若排在圆钮前面，恰恰会在最需要看见"锁钮高亮/放大"反馈的那一刻被圆钮整个盖住。
    // 锁钮固定在同一个位置、圆钮跟手飘过，锁钮必须画在上层才能全程可见。
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(
                x = anchorXDp + with(density) { dragOffset.x.toDp() }.coerceIn((-100).dp, 100.dp),
                y = anchorYDp + with(density) { dragOffset.y.toDp() }.coerceIn((-160).dp, 20.dp) - BIG_CIRCLE / 2,
            )
            .size(BIG_CIRCLE)
            .scale(ringScale)
            .clip(CircleShape)
            .background(c.accent),
        contentAlignment = Alignment.Center,
    ) {
        VoiceIcon(Lucide.Mic, null, c.onAccent, 26.dp)
    }
    // 上箭头呼吸（对齐 iOS `IMVoicePressOverlay.restartArrowBreathe`：position.y 上下 4pt 往复、
    // 0.7s、线性、无限重复）——引导"往上滑到这里锁定"，2026-09-28 用户对照 iOS 报本端锁钮缺这个提示。
    val arrowY by rememberInfiniteTransition(label = "lock-arrow").animateFloat(
        initialValue = 0f, targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "lock-arrow-y",
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = anchorXDp, y = anchorYDp - (LOCK_ABOVE + BIG_CIRCLE / 2))
            .size(36.dp, 52.dp)
            .scale(if (lockPhase != VoiceRules.LockPhase.None) 1.1f else 1f)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface),
    ) {
        // 锁图标在上（对齐按下点方向）、呼吸箭头在下（对齐 iOS：lockIcon center y=16，
        // lockArrow center y=52-16=36，同一个 52pt 高胶囊里上下各占一截）。
        VoiceIcon(
            Lucide.Lock, null, if (lockPhase != VoiceRules.LockPhase.None) c.accent else c.textSecondary, 16.dp,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 8.dp),
        )
        VoiceIcon(
            Lucide.ChevronUp, null, c.textSecondary, 14.dp,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = -8.dp + arrowY.dp),
        )
    }
}

/** 按住未锁定期间：整条栏 morph 成「红点 + 计时 + 居中的滑动取消提示」（草图 §03/§13）。 */
@Composable
internal fun VoiceRecordingRowContent(state: VoiceRecorder.State, dragOffset: Offset, rowWidthPx: Float, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val cancelReady = VoiceRules.cancelReady(dragOffset.x, rowWidthPx)
    val (hintOffsetPx, hintAlpha) = VoiceRules.slideHint(dragOffset.x, cancelReady)
    val countdown = VoiceRules.countdownSeconds(state.elapsedMs)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IMTheme.dimens.inputControl)
            .clip(RoundedCornerShape(IMTheme.dimens.inputControl / 2))
            .background(if (cancelReady) c.danger else androidx.compose.ui.graphics.Color.Transparent),
    ) {
        Row(modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (cancelReady) c.onAccent else c.danger))
            Spacer(Modifier.width(6.dp))
            Text(
                text = countdown?.let { stringResource(R.string.chat_voice_remaining, it) }
                    ?: MediaUrl.formatDuration(state.elapsedMs.toInt()),
                color = if (cancelReady) c.onAccent else if (countdown != null) c.danger else c.textPrimary,
                fontSize = 14.sp,
            )
        }
        Text(
            text = stringResource(
                if (cancelReady) R.string.chat_voice_release_to_cancel else R.string.chat_voice_slide_to_cancel,
            ),
            color = if (cancelReady) c.onAccent else c.textSecondary,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = with(LocalDensity.current) { hintOffsetPx.toDp() })
                .alpha(if (cancelReady) 1f else hintAlpha),
        )
    }
}

/** 锁定行（草图 §04/§14）：🗑 删除 ｜ 胶囊（录制中跑马灯 / 暂停后迷你播放器）｜ ⏸/▶ ｜ ➤ 发送。 */
@Composable
internal fun VoiceLockedRow(convId: String, recorder: VoiceRecorder, state: VoiceRecorder.State, onToast: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val scope = rememberCoroutineScope()
    val player = LocalVoicePlayer.current
    var confirmDelete by remember { mutableStateOf(false) }
    val previewId = remember(convId) { PREVIEW_ID_PREFIX + convId }
    val playbackState: State<VoicePlayback?> = player?.state?.collectAsState() ?: remember { mutableStateOf(null) }
    val pb = playbackState.value?.takeIf { it.id == previewId && it.convId == convId }
    val paused = state.phase == VoiceRecorder.Phase.Paused

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(d.inputControl).clip(CircleShape).clickable {
                if (state.elapsedMs > VoiceRules.DELETE_CONFIRM_MS) confirmDelete = true else recorder.cancel()
            },
            contentAlignment = Alignment.Center,
        ) {
            VoiceIcon(Lucide.Trash2, stringResource(R.string.common_delete), c.textSecondary)
        }
        Spacer(Modifier.width(d.space2))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.pageBackground)
                .then(
                    if (paused) {
                        Modifier.clickable {
                            scope.launch {
                                val f = recorder.previewFile()
                                if (f == null) {
                                    onToast(Str.s(R.string.chat_voice_preview_prepare_failed))
                                } else {
                                    player?.toggleFile(previewId, convId, f, state.elapsedMs) { onToast(it) }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val waveform = remember(state.elapsedMs / 100, state.phase) {
                val amps = recorder.amplitudes()
                VoiceRules.encodeWaveform(ByteArray(amps.size) { (amps[it] * 100f).toInt().coerceIn(0, 100).toByte() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!paused) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.danger))
                    Spacer(Modifier.width(6.dp))
                    Text(MediaUrl.formatDuration(state.elapsedMs.toInt()), color = c.textPrimary, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    WaveformBars(
                        waveform = waveform, progress = 1f, active = c.voiceWaveInactive, inactive = c.voiceWaveInactive,
                        modifier = Modifier.weight(1f).height(20.dp),
                    )
                } else {
                    VoiceIcon(if (pb?.playing == true) Lucide.Pause else Lucide.Play, null, c.accent, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    val shown = VoiceRules.shownMillis(state.elapsedMs, pb?.progress ?: 0f, pb?.playing == true)
                    Text(MediaUrl.formatDuration(shown.toInt()), color = c.textPrimary, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    WaveformBars(
                        waveform = waveform, progress = pb?.progress ?: 0f, active = c.accent, inactive = c.voiceWaveInactive,
                        modifier = Modifier.weight(1f).height(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(d.space2))
        Box(
            modifier = Modifier.size(d.inputControl).clip(CircleShape).clickable {
                if (paused) {
                    player?.stop() // 继续录音要抢设备，先放掉试听
                    recorder.resume()
                } else {
                    recorder.pause()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            VoiceIcon(if (paused) Lucide.Play else Lucide.Pause, null, c.textSecondary)
        }
        Spacer(Modifier.width(d.space2))
        Box(
            modifier = Modifier.size(d.inputControl).clip(CircleShape).background(c.accent).clickable {
                player?.stop()
                recorder.stopAndSend()
            },
            contentAlignment = Alignment.Center,
        ) {
            VoiceIcon(Lucide.SendHorizontal, stringResource(R.string.common_send), c.onAccent)
        }
    }

    if (confirmDelete) {
        IMConfirmDialog(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.chat_voice_delete_recording_confirm),
            confirmText = stringResource(R.string.common_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = { recorder.cancel() },
        )
    }
}

/**
 * 录音机事件 → toast / 发送 / 锁定态三件事的唯一分派点（[ComposerBar] 挂一次）。
 * @param locked 当前是不是已经锁定（免提）——决定 [VoiceRecorder.Event.ReachedMax] 时是转锁定暂停还是直接发。
 */
@Composable
internal fun VoiceRecordEvents(
    convId: String,
    locked: Boolean,
    onToast: (String) -> Unit,
    onSentVoice: (File, Int, String?) -> Unit,
    onReset: () -> Unit,
    onLocked: () -> Unit,
) {
    val recorder = LocalVoiceRecorder.current ?: return
    LaunchedEffect(recorder, convId, locked) {
        recorder.events.collect { event ->
            when (event) {
                is VoiceRecorder.Event.Stopped -> {
                    if (event.convId != convId) return@collect
                    onReset()
                    when (event.reason) {
                        VoiceRecorder.StopReason.UserSend, VoiceRecorder.StopReason.ReachedMax -> {
                            val f = event.file
                            if (f != null) onSentVoice(f, event.durationMs.toInt(), event.waveform)
                            else onToast(Str.s(R.string.chat_voice_process_failed))
                        }
                        VoiceRecorder.StopReason.TooShort -> onToast(Str.s(R.string.chat_voice_too_short))
                        VoiceRecorder.StopReason.Error -> onToast(Str.s(R.string.chat_voice_cannot_start))
                        VoiceRecorder.StopReason.UserCancel -> {} // 静默丢弃（设计 §5.1「取消消散」，不打扰用户）
                    }
                }
                is VoiceRecorder.Event.ReachedMax -> {
                    if (event.convId != convId) return@collect
                    if (locked) {
                        // 锁定态 = 用户已"设置并忘记"，超时即发是他期望的行为（草图 §12 情形 B）
                        onToast(Str.s(R.string.chat_voice_max_reached, (VoiceRules.MAX_MS / 60_000L).toInt()))
                        recorder.stopAndSend()
                    } else {
                        // 按住态可能正说到一半，转锁定+暂停等用户决定（草图 §12 情形 A），不打断、不弹 toast
                        recorder.pause()
                        onLocked()
                    }
                }
                is VoiceRecorder.Event.Interrupted -> if (event.convId == convId) onLocked()
            }
        }
    }
}

@Composable
private fun VoiceIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
