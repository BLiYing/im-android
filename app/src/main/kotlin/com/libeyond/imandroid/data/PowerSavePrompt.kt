package com.libeyond.imandroid.data

/**
 * §5 自动开启提示的「每个放电周期一次」状态机（纯逻辑，单测钉住）。
 *
 * - 触发：`active` 由 false 变 true **且原因是电量**（手动「始终开启」、跟随系统都不提示）；
 * - 每个放电周期一次：开始充电即重置；
 * - 后台触发的：回前台 [LATE_WINDOW_MS]（10 分钟）内补弹，超时不补。
 */
data class PowerSavePromptState(
    val prevActive: Boolean = false,
    /** 本周期已提示（或已排队补弹）。 */
    val shown: Boolean = false,
    /** 后台触发的时刻；回前台时据此判补弹。 */
    val pendingAtMs: Long? = null,
)

data class PromptStep(val state: PowerSavePromptState, val showNow: Boolean)

object PowerSavePrompt {
    const val LATE_WINDOW_MS = 10 * 60 * 1000L

    /**
     * 进程重启后「本周期已提示」是否仍有效：持久化为真，**且**此刻仍是电量触发的生效态才算同一个放电周期；
     * 否则（已充电 / 电量回到阈值以上 / 换了开启方式）视为新周期。
     */
    fun startupShown(persisted: Boolean, reason: PowerSaveReason?): Boolean =
        persisted && reason == PowerSaveReason.BATTERY

    /** 状态（电量 / 偏好 / 充电）变化时调。 */
    fun onStatus(
        s: PowerSavePromptState,
        active: Boolean,
        reason: PowerSaveReason?,
        charging: Boolean?,
        foreground: Boolean,
        nowMs: Long,
    ): PromptStep {
        var next = s.copy(prevActive = active)
        if (charging == true) next = next.copy(shown = false, pendingAtMs = null)
        val rising = active && !s.prevActive && reason == PowerSaveReason.BATTERY
        if (!rising || next.shown) return PromptStep(next, false)
        return if (foreground) {
            PromptStep(next.copy(shown = true, pendingAtMs = null), true)
        } else {
            PromptStep(next.copy(shown = true, pendingAtMs = nowMs), false)
        }
    }

    /** 回到前台时调：后台排队的，10 分钟内补弹一次。 */
    fun onForeground(s: PowerSavePromptState, stillActive: Boolean, nowMs: Long): PromptStep {
        val at = s.pendingAtMs ?: return PromptStep(s, false)
        val show = stillActive && nowMs - at in 0..LATE_WINDOW_MS
        return PromptStep(s.copy(pendingAtMs = null), show)
    }
}
