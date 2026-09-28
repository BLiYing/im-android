package com.libeyond.imandroid.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import com.libeyond.imandroid.sdk.logging.IMLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音录制（设计 VOICE_MESSAGE_DESIGN §4/§5，对齐 iOS `IMVoiceRecorder`）。**进程内一份**，记住录音属于哪个会话：
 * 中断（来电 / 切后台 / 离开聊天页）后停在暂停态，回到原会话锁定行还在（§5.4），App 被杀才丢（同微信）。
 *
 * - 编码 AAC-LC / 单声道 / 16kHz / 24kbps（§4 两端统一参数；**绝不产出 Opus**，§7.9）。
 * - 多段模型：暂停 = 真正收尾当前段（暂停中才能试听），继续 = 新起一段，发送时拼接（[VoiceSegments]）。
 * - 计时按墙钟、**暂停时长不算**（iOS `startedAtMillis += pauseDur`，两端口径必须一致）。
 * - 5min 双硬闸：采样 tick 里到点通知一次 [Event.ReachedMax] 由界面决定（按住态转锁定暂停 / 锁定态直接发）；
 *   `setMaxDuration` 是系统层兜底。
 */
class VoiceRecorder(context: Context, private val player: VoicePlayer) {

    enum class Phase { Idle, Recording, Paused }

    enum class StopReason { UserSend, UserCancel, TooShort, ReachedMax, Error }

    data class State(
        val phase: Phase = Phase.Idle,
        val convId: String = "",
        val elapsedMs: Long = 0,
        /** 最近一帧振幅 0..1（呼吸环 / 跑马灯）。 */
        val amplitude: Float = 0f,
    ) {
        val active: Boolean get() = phase != Phase.Idle
    }

    sealed interface Event {
        /** 录制结束。发送类原因（UserSend / ReachedMax）带文件；[file] 为 null = 处理失败（合并失败等），不能装作发出去。 */
        data class Stopped(
            val reason: StopReason,
            val convId: String,
            val file: File?,
            val waveform: String?,
            val durationMs: Long,
        ) : Event
        /** 录满上限（只通知一次），界面据当前是否锁定决定发送还是转锁定暂停。 */
        data class ReachedMax(val convId: String) : Event
        /** 被来电 / 切后台打断，已自动暂停（≥0.6s 才走这里，更短的按太短丢弃）。 */
        data class Interrupted(val convId: String) : Event
    }

    private val app = context.applicationContext
    private val log = IMLog.tag("IM.Voice")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val workDir = File(app.filesDir, "voice_rec").apply { mkdirs() }
    /** 待发语音的落点：**不放缓存目录**——上传失败后要能重传（iOS 同样挪进 IMPendingMediaStore）。 */
    val pendingDir = File(app.filesDir, "voice_pending").apply { mkdirs() }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private val segments = ArrayList<File>()
    private val samples = ByteArrayOutputStream()
    private var startedAt = 0L
    private var pausedAt = 0L
    private var maxNotified = false
    private var ticker: Job? = null
    private var previewCache: File? = null

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .setOnAudioFocusChangeListener { change ->
            // 来电等抢走焦点 = 中断（§5.4）
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) interrupt()
        }
        .build()

    val elapsedMs: Long get() = when (_state.value.phase) {
        Phase.Idle -> 0
        Phase.Paused -> (pausedAt - startedAt).coerceAtLeast(0)
        Phase.Recording -> (now() - startedAt).coerceAtLeast(0)
    }

    /** 已录振幅（0..1），试听态胶囊画完整波形用。 */
    fun amplitudes(): FloatArray = samples.toByteArray().let { b -> FloatArray(b.size) { (b[it].toInt() and 0xFF) / 100f } }

    /** 开始录（上一轮若还在——比如别的会话里停着的暂停录音——整轮丢弃）。失败返回 false 并已发 Error 事件。 */
    fun start(convId: String): Boolean {
        if (_state.value.active) discard()
        player.stop() // 录音要抢设备：正在播的语音停掉
        samples.reset()
        maxNotified = false
        audio.requestAudioFocus(focusRequest)
        startedAt = now()
        if (!armSegment(alreadyMs = 0)) {
            audio.abandonAudioFocusRequest(focusRequest)
            _events.tryEmit(Event.Stopped(StopReason.Error, convId, null, null, 0))
            return false
        }
        _state.value = State(Phase.Recording, convId, 0, 0f)
        startTicker()
        log.i("voice_record_start")
        return true
    }

    /** 暂停：收尾当前段（可试听）。 */
    fun pause() {
        if (_state.value.phase != Phase.Recording) return
        finishSegment()
        pausedAt = now()
        stopTicker()
        _state.value = _state.value.copy(phase = Phase.Paused, elapsedMs = elapsedMs, amplitude = 0f)
    }

    /** 继续：新起一段，墙钟补回暂停时长。新段起不来就留在暂停态（用户仍可发已录的 / 删除）。 */
    fun resume() {
        if (_state.value.phase != Phase.Paused) return
        player.stop()
        val already = (pausedAt - startedAt).coerceAtLeast(0)
        startedAt += (now() - pausedAt).coerceAtLeast(0)
        audio.requestAudioFocus(focusRequest)
        if (!armSegment(already)) return
        _state.value = _state.value.copy(phase = Phase.Recording)
        startTicker()
    }

    fun cancel() {
        if (!_state.value.active) return
        val conv = _state.value.convId
        discard()
        _events.tryEmit(Event.Stopped(StopReason.UserCancel, conv, null, null, 0))
    }

    fun stopAndSend() = finish(StopReason.UserSend)

    /**
     * 试听文件：单段直接用；多段合并成一个临时文件（每次重新合并、旧的先删——iOS 曾泄漏过这个临时件）。
     * 录制中不能试听（先暂停）。
     */
    suspend fun previewFile(): File? {
        if (_state.value.phase != Phase.Paused || segments.isEmpty()) return null
        if (segments.size == 1) return segments.first()
        previewCache?.delete()
        val out = File(workDir, "preview-${UUID.randomUUID()}.m4a")
        val ok = withContext(Dispatchers.IO) { VoiceSegments.merge(segments.toList(), out) }
        return if (ok) out.also { previewCache = it } else null
    }

    /** 被打断（来电 / 切后台 / 离开聊天页）：太短直接丢，否则自动暂停并通知（§5.4）。 */
    fun interrupt() {
        if (_state.value.phase != Phase.Recording) return
        val conv = _state.value.convId
        if (elapsedMs < VoiceRules.SHORT_MS) {
            discard()
            _events.tryEmit(Event.Stopped(StopReason.TooShort, conv, null, null, elapsedMs))
            return
        }
        pause()
        _events.tryEmit(Event.Interrupted(conv))
    }

    // ————————————————— 内部 —————————————————

    private fun finish(reason: StopReason) {
        val st = _state.value
        if (!st.active) return
        val dur = elapsedMs
        val waveform = VoiceRules.encodeWaveform(samples.toByteArray())
        val segs = teardown()
        if (dur < VoiceRules.SHORT_MS || segs.isEmpty()) {
            segs.forEach { it.delete() }
            _events.tryEmit(Event.Stopped(StopReason.TooShort, st.convId, null, null, dur))
            return
        }
        scope.launch {
            val out = File(pendingDir, "${UUID.randomUUID()}.m4a")
            val ok = withContext(Dispatchers.IO) {
                if (segs.size == 1) segs[0].renameTo(out) || runCatching { segs[0].copyTo(out, true); true }.getOrDefault(false)
                else VoiceSegments.merge(segs, out)
            }
            segs.forEach { if (it.exists()) it.delete() }
            log.i("voice_record_done", "durationMs" to dur, "segments" to segs.size, "ok" to ok)
            _events.tryEmit(Event.Stopped(reason, st.convId, if (ok) out else null, waveform, dur))
        }
    }

    /** 整轮丢弃（取消 / 太短 / 被新一轮顶掉），不发事件。 */
    private fun discard() {
        teardown().forEach { it.delete() }
        currentFile?.delete() // teardown() 已经会经 finishSegment() 清空它，这里是留了道保险
        currentFile = null
    }

    /**
     * [finish]/[discard] 共同的收尾步骤：收当前段（如果还在录）、停 ticker、清空状态、还音频焦点、
     * 扔掉试听缓存文件。**不删分段文件**——留给调用方决定（[finish] 还要用它们拼最终文件）。
     */
    private fun teardown(): List<File> {
        if (_state.value.phase == Phase.Recording) finishSegment()
        stopTicker()
        val segs = segments.toList()
        segments.clear()
        _state.value = State()
        audio.abandonAudioFocusRequest(focusRequest)
        cleanupPreview()
        return segs
    }

    private fun cleanupPreview() {
        previewCache?.delete()
        previewCache = null
    }

    /** @param alreadyMs 本轮之前各段已录的时长——系统兜底闸只给「总上限 - 已录」。 */
    private fun armSegment(alreadyMs: Long): Boolean {
        val f = File(workDir, "${UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(24_000)
            // 系统层兜底闸：本段最多录到「总上限 - 已录」（UI 软闸在 tick 里，时序不同不冲突）。
            r.setMaxDuration((VoiceRules.MAX_MS - alreadyMs).coerceAtLeast(1000).toInt())
            r.setOnInfoListener { _, what, _ ->
                // 硬闸真触发时录音已经被系统收尾，这里只能安全地"收好这一段"（同 pause()），
                // **不能替用户决定要不要发**——「锁定态到点直接发 / 按住态到点转锁定」的判断
                // 是唯一决策点，在 VoiceRecordEvents 消费 Event.ReachedMax 那里（与 tick 软闸共用）。
                // 之前这里直接 finish(ReachedMax)：硬闸与软闸目标时长一致、硬闸几乎总赢，
                // 于是按住态到点也被当 UserSend 强制发送，design 要的"转锁定等用户决定"从没生效过
                // （2026-09-28 code review 抓出）。
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    pause()
                    notifyReachedMax()
                }
            }
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            recorder = r
            currentFile = f
            true
        } catch (e: Exception) {
            log.w("voice_record_arm_failed", "err" to (e.message ?: e.javaClass.simpleName))
            r.release()
            f.delete()
            false
        }
    }

    /** 收尾当前段：stop 成功才算一段；录得太短 `stop()` 会抛（没有有效数据），那段直接丢。 */
    private fun finishSegment() {
        val r = recorder ?: return
        val f = currentFile
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        currentFile = null
        if (f != null) { if (ok && f.length() > 0) segments += f else f.delete() }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive && _state.value.phase == Phase.Recording) {
                delay(SAMPLE_MS)
                val r = recorder ?: break
                val amp = VoiceRules.amplitudeOf(runCatching { r.maxAmplitude }.getOrDefault(0))
                if (samples.size() < 4096) samples.write(VoiceRules.amplitudeByte(amp).toInt())
                val el = elapsedMs
                _state.value = _state.value.copy(elapsedMs = el, amplitude = amp)
                if (el >= VoiceRules.MAX_MS) notifyReachedMax()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** 到点通知只发一次，硬闸（系统 `setMaxDuration`）与软闸（本 tick）共用同一个闸门。 */
    private fun notifyReachedMax() {
        if (maxNotified) return
        maxNotified = true
        _events.tryEmit(Event.ReachedMax(_state.value.convId))
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val SAMPLE_MS = 100L
    }
}
