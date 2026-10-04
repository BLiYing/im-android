package com.libeyond.imandroid.data

/** 省电模式开启方式（POWER_SAVING_DESIGN §3；wire 值与向量、三端存储一致）。 */
enum class PowerSaveMode(val wire: String) {
    OFF("off"),
    AUTO("auto"),
    ALWAYS("always"),
    ;

    companion object {
        /** 未知 / 缺失回落关闭。 */
        fun fromWire(wire: String?): PowerSaveMode = entries.firstOrNull { it.wire == wire } ?: OFF
    }
}

/**
 * `powerSaveActive` 的输入（四端同名同形，向量 `docs/conformance/power_save.json`）。
 * `level` 为 0..100，null = 读不到；`charging` / `systemSaver` 为 null = 未知。
 */
data class PowerSaveContext(
    val mode: PowerSaveMode,
    val threshold: Int,
    val level: Int?,
    val charging: Boolean?,
    val followSystem: Boolean,
    val systemSaver: Boolean?,
)

/** 生效原因（状态行副标题用）。优先级：始终开启 > 电量 > 跟随系统。 */
enum class PowerSaveReason { ALWAYS, BATTERY, SYSTEM }

/**
 * 省电模式判定（纯函数，真相是向量 `power_save.json`；改规则先改向量）。
 *
 * `charging == null` 不触发 auto（宁可不省电，也不在插着电时误开）；`level == threshold` 算触发。
 */
object PowerSaveDecision {
    const val THRESHOLD_MIN = 5
    const val THRESHOLD_MAX = 50
    const val THRESHOLD_STEP = 5
    const val THRESHOLD_DEFAULT = 15

    /** 越界夹到边界（不对齐步长：向量里只有越界用例，步长由滑块保证）。 */
    fun clampThreshold(t: Int): Int = t.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)

    fun reason(ctx: PowerSaveContext): PowerSaveReason? = when {
        ctx.mode == PowerSaveMode.ALWAYS -> PowerSaveReason.ALWAYS
        ctx.mode == PowerSaveMode.AUTO && ctx.level != null && ctx.charging == false &&
            ctx.level <= clampThreshold(ctx.threshold) -> PowerSaveReason.BATTERY
        ctx.followSystem && ctx.systemSaver == true -> PowerSaveReason.SYSTEM
        else -> null
    }

    fun active(ctx: PowerSaveContext): Boolean = reason(ctx) != null
}

fun powerSaveActive(ctx: PowerSaveContext): Boolean = PowerSaveDecision.active(ctx)
