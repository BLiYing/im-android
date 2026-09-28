package com.libeyond.imandroid.voice

import com.libeyond.imandroid.data.Waveform
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 语音播放的纯规则（设计 VOICE_MESSAGE_DESIGN §6/§7），与 iOS `IMVoicePlayer` / `IMChatViewController+Voice`、
 * im-web `VoiceBubble` / `voiceRelay.ts` 逐条对齐。抽出来是为了单测钉住——三端各写一份时最容易各自漂移。
 */
object VoiceRules {

    /** 倍速三档（§6.2 `1x→1.5x→2x`）。 */
    val RATES = floatArrayOf(1f, 1.5f, 2f)

    /**
     * 规范化到三档：UI 只提供这三档，其他值属程序化脏数据，兜底 1.0（iOS `setRate:forConvID:` 同口径）。
     */
    fun normalizeRate(r: Float): Float = when {
        kotlin.math.abs(r - 1.5f) < 0.05f -> 1.5f
        kotlin.math.abs(r - 2f) < 0.05f -> 2f
        else -> 1f
    }

    /** 倍速胶囊点一下：1x → 1.5x → 2x → 1x。 */
    fun nextRate(r: Float): Float = when (normalizeRate(r)) {
        1f -> 1.5f
        1.5f -> 2f
        else -> 1f
    }

    /** 胶囊文案：`1x` / `1.5x` / `2x`（三端同一写法，不本地化）。 */
    fun rateLabel(r: Float): String = when (normalizeRate(r)) {
        1.5f -> "1.5x"
        2f -> "2x"
        else -> "1x"
    }

    /**
     * 一条语音在本机播放器 / 已播集合里的标识：**已确认用 `conv_seq`**，未确认（自己刚发、还没 ack）用 clientMsgId。
     *
     * 与 iOS（serverMsgID 优先）刻意不同：本端资料页「语音」归档条目（服务端 `ConvMediaItem`）只有 conv_seq、
     * 没有消息 id，用 conv_seq 才能让「在资料页点过的语音，聊天页红点也消失」、两处播放状态同步。
     * 已播集合按会话分键，conv_seq 在会话内唯一，够用；标识只在本机流转，不跨端。
     */
    fun playableId(convSeq: Long, clientMsgId: String): String? =
        if (convSeq > 0) "seq:$convSeq" else clientMsgId.ifBlank { null }?.let { "cid:$it" }

    fun playableId(m: MessageEntity): String? = playableId(m.convSeq, m.clientMsgId)

    /**
     * 气泡宽度（dp）：`min(240, max(160, 96 + 秒 × 3.6))`，秒数下限 1（iOS `IMVoiceBubbleCell` 同式）。
     * 1 秒和 5 分钟看得出差别，再长也不撑破屏幕；下限 160 是给「时长 + 倍速胶囊」那一行留地方。
     */
    fun bubbleWidthDp(durationMs: Long): Float {
        val secs = maxOf(1f, durationMs / 1000f)
        return minOf(240f, maxOf(160f, 96f + secs * 3.6f))
    }

    /**
     * 进度条分母：播放器读到的时长**不可信时**用消息自带的 `duration`。
     *
     * 真实案例（2026-09-28 OPPO 实测）：Web 端 Chrome MediaRecorder 录的是**分片 MP4**，文件头里的总时长
     * 几乎是空的，`MediaPlayer.duration` 读出 11ms，而实际 6.7s——进度一开播就跳满、剩余时间显 0:00。
     * 判据：没有提示值就信播放器；播放器读到的不足提示值一半（或 ≤0）就用提示值。
     */
    fun effectiveDurationMs(playerMs: Long, hintMs: Long): Long = when {
        hintMs <= 0 -> maxOf(0L, playerMs)
        playerMs <= 0 || playerMs < hintMs / 2 -> hintMs
        else -> playerMs
    }

    /** 播放/暂停中显**剩余**时间，其余显总时长（§6.2 进度记忆那条）。 */
    fun shownMillis(totalMs: Long, progress: Float, active: Boolean): Long =
        if (active) maxOf(0L, totalMs - (progress.coerceIn(0f, 1f) * totalMs).toLong()) else totalMs

    // ————————————————— 录制（§5，iOS `IMVoiceRecorder` / `IMVoicePressOverlay` / `IMVoiceRecordingHUD`）—————————————————

    /** <0.6s 松手按「说话时间太短」丢弃（§5.1）。 */
    const val SHORT_MS = 600L

    /** 上限 5min（§1 协议 `maxVoiceDurationMillis`）。 */
    const val MAX_MS = 5 * 60 * 1000L

    /** 4:50 起计时变红并倒数（草图 §12）。 */
    const val COUNTDOWN_FROM_MS = MAX_MS - 10_000L

    /** 波形样本上限 60（服务端上限 120 字节留一半余量，iOS `IMVoiceWaveformMaxSamples`）。 */
    const val WAVEFORM_SAMPLES = 60

    /** 左滑取消阈值：输入行宽的 40%（§5.2，iOS `kIMVoiceCancelThresholdRatio`）。 */
    const val CANCEL_RATIO = 0.40f

    /** 已录超过 10s 删除才二次确认（§5.3）。 */
    const val DELETE_CONFIRM_MS = 10_000L

    /**
     * `MediaRecorder.getMaxAmplitude()`（0..32767，上次调用以来的峰值）→ 0..1。
     * 与 iOS `10^(dB/20)` 同为**线性幅度**，只是取峰值而非均方根——语音条要的是「哪里在说话」的形状，够用。
     */
    fun amplitudeOf(maxAmplitude: Int): Float = (maxAmplitude / 32767f).coerceIn(0f, 1f)

    /** 0..1 振幅 → 协议字节（0..100 的百分比）。 */
    fun amplitudeByte(a: Float): Byte = (a.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100).toByte()

    /**
     * 录到的逐帧振幅（0..100）→ `waveform`（base64，≤[WAVEFORM_SAMPLES] 字节）。
     * 超出按桶**取最大值**下采（保峰形），复用 [Waveform.bars] 的同一份算法——
     * 收端解码也靠它按显示宽度再下采一次，两处各写一份桶边界/取值逻辑最容易悄悄漂移
     * （见 `data/Waveform.kt` 文件头注释，2026-09-28 code review 抓出这里曾经重复实现）。
     * 不超限直传原样字节，不补齐到 [WAVEFORM_SAMPLES]——协议允许变长，短录音没必要把体积撑大。
     * 一帧没有 → null（收端退化等高条纹，合法状态）。
     */
    fun encodeWaveform(samples: ByteArray): String? {
        if (samples.isEmpty()) return null
        val raw = if (samples.size <= WAVEFORM_SAMPLES) {
            samples
        } else {
            val amps = FloatArray(samples.size) { (samples[it].toInt() and 0xFF) / 100f }
            val bars = Waveform.bars(amps, WAVEFORM_SAMPLES)
            ByteArray(WAVEFORM_SAMPLES) { kotlin.math.round(bars[it] * 100f).toInt().coerceIn(0, 100).toByte() }
        }
        return java.util.Base64.getEncoder().encodeToString(raw)
    }

    /** 锁钮判定三态（iOS `IMVoiceLockPhase`）。 */
    enum class LockPhase { None, Near, Locked }

    /**
     * 手指相对锁钮中心的判定：进 [nearR] 高亮、进 [snapR] 到位即锁（无需松手）；
     * **越过兜底**——快速上滑时两次采样间能跳过 70pt，手指直接从锁下方到上方、永不入圈，
     * 所以「高于锁中心且横向仍在 [nearR] 走廊内」也算锁定（iOS 2026-08 修过同一处）。
     * 坐标系任意，只要三者一致（y 向下为正）。
     */
    fun lockPhase(fx: Float, fy: Float, lx: Float, ly: Float, nearR: Float, snapR: Float): LockPhase {
        val dist = kotlin.math.hypot(fx - lx, fy - ly)
        val flewPast = fy < ly && kotlin.math.abs(fx - lx) <= nearR
        return when {
            dist <= snapR || flewPast -> LockPhase.Locked
            dist <= nearR -> LockPhase.Near
            else -> LockPhase.None
        }
    }

    /** 左滑位移（负数）是否过了取消阈值。判定只看手指原始位移，与提示文字的视觉位移分离（草图 §13）。 */
    fun cancelReady(dx: Float, rowWidth: Float): Boolean = rowWidth > 0 && -dx >= rowWidth * CANCEL_RATIO

    /**
     * 「‹ 向左滑动取消」的跟手：位移 ×0.4 阻尼、按比例线性渐隐到 0.2 兜底（像素或 dp 同口径，140 为满程）；
     * 过阈值后强制居中、不透明（草图 §13 v2.5）。返回 (横向偏移, 透明度)。
     */
    fun slideHint(dx: Float, cancelReady: Boolean): Pair<Float, Float> {
        if (cancelReady) return 0f to 1f
        val clamped = dx.coerceIn(-140f, 0f)
        return clamped * 0.4f to (1f + clamped / 140f).coerceIn(0.2f, 1f)
    }

    /** 4:50 起倒数的剩余整秒（向上取整）；之前返回 null（显普通计时）。 */
    fun countdownSeconds(elapsedMs: Long): Int? =
        if (elapsedMs < COUNTDOWN_FROM_MS) null else (((MAX_MS - elapsedMs).coerceAtLeast(0) + 999) / 1000).toInt()

    /**
     * 接力连播（§6.4）：从刚播完那条往后找**同会话**第一条未播放的语音。
     * - 遇到非语音消息即停（话题边界）；
     * - 撤回/删除的跳过；自己发的跳过（自己听自己没意义）；已播过的跳过。
     *
     * @param ordered 当前会话已确认消息，**按显示顺序**
     */
    fun nextRelay(
        ordered: List<MessageEntity>,
        finishedId: String,
        myUid: String,
        hasPlayed: (String) -> Boolean,
    ): MessageEntity? {
        val start = ordered.indexOfFirst { playableId(it) == finishedId }
        if (start < 0) return null
        for (i in start + 1 until ordered.size) {
            val m = ordered[i]
            if (m.contentType != ContentType.VOICE) return null
            if ((m.recalledAt ?: 0) > 0 || (m.deletedAt ?: 0) > 0) continue
            if (m.sender == myUid) continue
            val id = playableId(m) ?: continue
            if (hasPlayed(id)) continue
            return m
        }
        return null
    }
}
