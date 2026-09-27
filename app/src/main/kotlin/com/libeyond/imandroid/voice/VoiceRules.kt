package com.libeyond.imandroid.voice

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
