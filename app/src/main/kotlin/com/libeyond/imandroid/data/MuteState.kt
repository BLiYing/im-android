package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

/**
 * 定时免打扰判定 + 到期文案分类（NOTIFICATIONS_P1_DESIGN §4.3，第二批）。
 *
 * **到期由客户端自己判**：服务端不推到期帧、不跑定时任务（同 §5.5 `online_until` 的做法）。
 * ⚠️ **凡读「是否免打扰」一律走 [isMutedNow]，不许再直接读 `muted`**——列表铃铛、页签未读、
 * 提示音判定、例外列表、选择页过滤都要换。漏一处就是「这里显示免打扰、那里照样响」
 * （SYMMETRY.md 已登记，对端 im-web `src/muteState.ts`、iOS `IMMuteState.*`）。
 *
 * 共用向量 `conformance/mute_state.json`（`isMutedNow` + `untilLabel` 两段）。
 */
object MuteState {

    /**
     * `muted && (muteUntilMs == 0 || nowMs < muteUntilMs)`。
     * **`now == until` 视为已解除**（与服务端 `conversation.EffectiveMute` 同）。
     */
    fun isMutedNow(muted: Boolean, muteUntilMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        muted && (muteUntilMs == 0L || nowMs < muteUntilMs)

    /** 到期文案的结构化分类（向量按这四个字段校验，文案拼装各端自己按 i18n 键做）。 */
    data class UntilLabel(
        val kind: Kind,
        /** `HH:mm`，仅 TODAY/TOMORROW 有值。 */
        val time: String? = null,
        /** 1-12，仅 DATE 有值。 */
        val month: Int? = null,
        val day: Int? = null,
    ) {
        enum class Kind { FOREVER, TODAY, TOMORROW, DATE }
    }

    /**
     * **只对有效免打扰（[isMutedNow] 为真）的会话调用**——过期/未免打扰调用这个没有意义。
     * 按 [zone] 指定的本地时区比较「日历日」：同一天 → TODAY，差一天 → TOMORROW，更晚 → DATE。
     *
     * [zone] 留成参数（不写死 `ZoneId.systemDefault()`）是为了让共用向量的 `tzOffsetMinutes`
     * 能被测试钉住——真实调用走默认值即可。
     */
    fun untilLabel(muteUntilMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): UntilLabel {
        if (muteUntilMs == 0L) return UntilLabel(UntilLabel.Kind.FOREVER)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val until = Instant.ofEpochMilli(muteUntilMs).atZone(zone)
        val time = String.format(Locale.getDefault(), "%02d:%02d", until.hour, until.minute)
        return when (until.toLocalDate().toEpochDay() - now.toEpochDay()) {
            0L -> UntilLabel(UntilLabel.Kind.TODAY, time = time)
            1L -> UntilLabel(UntilLabel.Kind.TOMORROW, time = time)
            else -> UntilLabel(UntilLabel.Kind.DATE, month = until.monthValue, day = until.dayOfMonth)
        }
    }

    /**
     * 「至……」短语（不含永久）：`至今天 11:00` / `至明天 07:30` / `至 10月6日`。
     * **forever 返回 null**——例外列表要用它区分「免打扰」(纯文本) 与「免打扰{至……}」(带参数) 两条文案，
     * 见 `ui/screens/NotificationTypeScreen.kt` 的 `ExceptionRow`。
     */
    fun untilPhrase(muteUntilMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val label = untilLabel(muteUntilMs, nowMs, zone)
        return when (label.kind) {
            UntilLabel.Kind.FOREVER -> null
            UntilLabel.Kind.TODAY -> Str.s(R.string.notif_mute_until_today, label.time!!)
            UntilLabel.Kind.TOMORROW -> Str.s(R.string.notif_mute_until_tomorrow, label.time!!)
            UntilLabel.Kind.DATE -> Str.s(R.string.notif_mute_until_date, monthDayOf(label.month!!, label.day!!))
        }
    }

    /** 聊天信息页「消息免打扰」行的右值：`永久` 或 [untilPhrase]。 */
    fun untilText(muteUntilMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        untilPhrase(muteUntilMs, nowMs, zone) ?: Str.s(R.string.common_permanent)

    /** 「至 {date}」的 date 部分——复用既有的本地化月日格式化（`TimeFormat` 同一套 `time_month_day`）。 */
    private fun monthDayOf(month: Int, day: Int): String {
        val cal = Calendar.getInstance().apply { set(Calendar.MONTH, month - 1) }
        return Str.s(R.string.time_month_day, Str.monthArg(cal), day)
    }

    /**
     * 本机会话表里「最近的一个未到期 mute_until」（§4.4 到期刷新用）：挂一个精确定时器到这个时刻，
     * 到点后免打扰的铃铛/未读徽标/页签角标/例外列表会跟着重组——不需要服务端推帧。
     * 没有任何定时免打扰会话时返回 null（不挂定时器）。
     */
    fun nearestFutureMuteUntil(conversations: List<ConversationEntity>, nowMs: Long): Long? =
        conversations.filter { it.muted && it.muteUntil > nowMs }.minOfOrNull { it.muteUntil }
}
