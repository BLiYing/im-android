package com.libeyond.imandroid.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.MediaDownloader
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.logging.IMLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 播放器此刻在播 / 暂停的那一条。`null` = 空闲。 */
data class VoicePlayback(
    val id: String,
    val convId: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** 只有聊天列表里的气泡参与接力；迷你播放器（收藏 / 资料页 / 记录页）与试听播完即停。 */
    val relayable: Boolean,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** 一条语音**自然播完**（≠ 主动 stop）。接力连播据此触发下一条。 */
data class VoiceFinished(val id: String, val convId: String, val relayable: Boolean)

/**
 * 语音播放器（设计 VOICE_MESSAGE_DESIGN §6）：**进程内一份，一次只播一条**，对齐 iOS `IMVoicePlayer` 单例。
 *
 * - 状态走 [state]（StateFlow），气泡 / 迷你播放器各自订阅、按 id 取自己那条；进度 tick 30fps
 *   （波形填充不需要 60fps，同 iOS `preferredFramesPerSecond = 30`）。
 * - 切到别的条先 stop 旧的；同一条再点 = 暂停 / 继续，**暂停保留位点**（§6.2 进度记忆）。
 * - 抢音频焦点、播完 / 停止即归还：不还的话用户正在听的音乐就没了（§4.1 同一条坑）。
 * - 解不了的格式**播前挡掉并给可读提示**（§7.9）：Android 不会像 iOS 那样除零崩，
 *   但静默没声音一样是「点了没反应」，比报错更难查。
 */
class VoicePlayer(
    context: Context,
    private val downloads: MediaDownloader,
    private val owner: () -> String,
) {
    private val log = IMLog.tag("IM.Voice")
    /** MediaPlayer 回调与进度 tick 都在主线程（Compose 读 [state] 也在主线程），不借 IMClient 的后台作用域。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val kv = PrefsVoiceKv(context)
    val played = VoicePlayedStore(kv)
    val rates = VoiceRateStore(kv)

    private val _state = MutableStateFlow<VoicePlayback?>(null)
    val state: StateFlow<VoicePlayback?> = _state

    private val _finished = MutableSharedFlow<VoiceFinished>(extraBufferCapacity = 4)
    val finished: SharedFlow<VoiceFinished> = _finished

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var pendingLoad: Job? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            // 来电 / 别的 App 抢走焦点 → 就地暂停（保留位点，回来接着听）
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    fun hasPlayed(convId: String, id: String): Boolean = played.hasPlayed(owner(), convId, id)

    fun markPlayed(convId: String, id: String) = played.markPlayed(owner(), convId, id)

    /**
     * 聊天气泡 / 迷你播放器共用入口：本地有文件直接播，没有先下（语音 <1MB，恒自动下载，
     * 走到这里多半是被清了缓存）。失败经 [onError] 回可读文案，**调用方负责吐司，别吞**。
     *
     * @param url 媒体相对地址（与下载缓存同一个键）
     */
    fun toggle(
        id: String,
        convId: String,
        url: String,
        relayable: Boolean,
        /** 消息自带的 `duration`（协议里 voice 必填）——播放器读到的时长不可信时用它，见 [VoiceRules.effectiveDurationMs]。 */
        durationHintMs: Long = 0,
        onError: (String) -> Unit,
    ) {
        val cur = _state.value
        if (cur != null && cur.id == id && cur.convId == convId && player != null) {
            if (cur.playing) pause() else resume()
            return
        }
        if (url.isBlank()) { onError(Str.s(R.string.chat_voice_content_empty)); return }
        val local = downloads.localFile(url)
        if (local != null) {
            startFile(id, convId, local, relayable, durationHintMs, onError)
            return
        }
        pendingLoad?.cancel()
        downloads.start(url)
        pendingLoad = scope.launch {
            // states 首帧即当前值；每次状态表变化重判一次这一条是否落定
            val phase = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                downloads.states.first { downloads.stateOf(url).phase in TERMINAL }
                downloads.stateOf(url).phase
            }
            val f = downloads.localFile(url)
            if (phase == DownloadPhase.Ready && f != null) {
                startFile(id, convId, f, relayable, durationHintMs, onError)
            } else {
                log.w("voice_download_failed", "phase" to (phase?.name ?: "timeout"))
                onError(Str.s(R.string.chat_voice_download_failed))
            }
        }
    }

    /** 直接播一个本地文件（录制试听用，`id` 用固定的试听标识）。 */
    fun toggleFile(id: String, convId: String, file: File, durationHintMs: Long = 0, onError: (String) -> Unit) {
        val cur = _state.value
        if (cur != null && cur.id == id && cur.convId == convId && player != null) {
            if (cur.playing) pause() else resume()
            return
        }
        startFile(id, convId, file, relayable = false, durationHintMs = durationHintMs, onError = onError)
    }

    private fun startFile(
        id: String,
        convId: String,
        file: File,
        relayable: Boolean,
        durationHintMs: Long,
        onError: (String) -> Unit,
    ) {
        stop()
        if (!isPlayable(file)) {
            log.w("voice_unplayable", "bytes" to file.length())
            onError(Str.s(R.string.chat_voice_format_unsupported))
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.path)
            mp.prepare() // 本地小文件（<1MB），同步准备与 iOS prepareToPlay 同量级
        } catch (e: Exception) {
            mp.release()
            log.w("voice_prepare_failed", "err" to (e.message ?: e.javaClass.simpleName))
            onError(Str.s(R.string.chat_voice_format_unsupported))
            return
        }
        mp.setOnCompletionListener { onNaturalFinish() }
        mp.setOnErrorListener { _, what, extra ->
            log.w("voice_play_error", "what" to what, "extra" to extra)
            stop()
            onError(Str.s(R.string.chat_voice_format_unsupported))
            true
        }
        player = mp
        val dur = VoiceRules.effectiveDurationMs(mp.duration.toLong(), durationHintMs)
        log.i("voice_play_start", "playerMs" to mp.duration, "hintMs" to durationHintMs, "bytes" to file.length())
        _state.value = VoicePlayback(id, convId, playing = false, positionMs = 0, durationMs = dur, relayable = relayable)
        resume()
    }

    private fun resume() {
        val mp = player ?: return
        val cur = _state.value ?: return
        audio.requestAudioFocus(focusRequest)
        mp.start()
        // 倍速只能在**播放中**设：在已准备但暂停的播放器上设 speed 会直接把它启动起来
        applyRate(mp, rates.rateFor(cur.convId))
        _state.value = cur.copy(playing = true)
        startTicker()
    }

    /** 就地暂停（保留位点）。离开会播语音的页面 / App 切后台 / 失去音频焦点时调。 */
    fun pause() {
        val mp = player ?: return
        val cur = _state.value ?: return
        if (!cur.playing) return
        runCatching { mp.pause() }
        stopTicker()
        _state.value = cur.copy(playing = false, positionMs = mp.currentPosition.toLong())
    }

    /** 这条不放了（切到另一条 / 录音要抢设备），丢弃位点回到空闲。 */
    fun stop() {
        pendingLoad?.cancel()
        val mp = player ?: return
        stopTicker()
        runCatching { mp.stop() }
        mp.release()
        player = null
        _state.value = null
        audio.abandonAudioFocusRequest(focusRequest)
    }

    /** 拖拽 scrub：仅对正在播 / 暂停的那条生效。 */
    fun seek(id: String, fraction: Float) {
        val mp = player ?: return
        val cur = _state.value ?: return
        if (cur.id != id || cur.durationMs <= 0) return
        val pos = (fraction.coerceIn(0f, 1f) * cur.durationMs).toLong()
        mp.seekTo(pos.toInt())
        _state.value = cur.copy(positionMs = pos)
    }

    fun setRate(convId: String, rate: Float) {
        rates.setRate(convId, rate)
        val mp = player ?: return
        val cur = _state.value ?: return
        if (cur.convId == convId && cur.playing) applyRate(mp, rate)
    }

    private fun applyRate(mp: MediaPlayer, rate: Float) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(VoiceRules.normalizeRate(rate)) }
            .onFailure { log.w("voice_rate_failed", "err" to (it.message ?: "")) }
    }

    private fun onNaturalFinish() {
        val cur = _state.value ?: return
        stopTicker()
        player?.release()
        player = null
        _state.value = null
        audio.abandonAudioFocusRequest(focusRequest)
        _finished.tryEmit(VoiceFinished(cur.id, cur.convId, cur.relayable))
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                val cur = _state.value ?: break
                _state.value = cur.copy(positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(cur.positionMs))
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    companion object {
        private const val TICK_MS = 33L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private val TERMINAL = setOf(DownloadPhase.Ready, DownloadPhase.Failed, DownloadPhase.Expired, DownloadPhase.Paused)

        /**
         * 能不能交给 MediaPlayer：要有一条音频轨，且采样率 / 声道数都正常（iOS `IMVoiceFileIsPlayable` 的同款判据）。
         * 设计 §7.9 的那类坏文件（`.m4a` 壳里装 Opus、packet 参数全 0）在这里就挡掉。
         */
        fun isPlayable(file: File): Boolean {
            if (!file.isFile || file.length() <= 0) return false
            val ex = MediaExtractor()
            return try {
                ex.setDataSource(file.path)
                (0 until ex.trackCount).any { i ->
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    mime.startsWith("audio/") &&
                        f.getIntOr(MediaFormat.KEY_SAMPLE_RATE) > 0 &&
                        f.getIntOr(MediaFormat.KEY_CHANNEL_COUNT) > 0
                }
            } catch (_: Exception) {
                false
            } finally {
                ex.release()
            }
        }

        private fun MediaFormat.getIntOr(key: String): Int = if (containsKey(key)) getInteger(key) else 0
    }
}
