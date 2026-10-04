package com.libeyond.imandroid.data

/**
 * 省电模式的本机偏好快照（POWER_SAVING_DESIGN §6）。**每设备本地、退出登录保留、不上传**，
 * 写法同 [AppearancePrefs]。「界面动画」不在这里——它与外观页「动画」是同一个值
 * （[AppearancePrefs.animationsEnabled]）。
 */
data class PowerSavingPrefs(
    val mode: PowerSaveMode = PowerSaveMode.OFF,
    val threshold: Int = PowerSaveDecision.THRESHOLD_DEFAULT,
    val followSystem: Boolean = true,
    val autoDownload: Boolean = true,
    val videoPreload: Boolean = true,
    val backgroundConnection: Boolean = true,
    /** §5：本放电周期已提示过（持久化，进程重启不重复弹；充电或启动时不再是电量触发即清）。 */
    val promptShown: Boolean = false,
) {
    fun clamped(): PowerSavingPrefs = copy(threshold = PowerSaveDecision.clampThreshold(threshold))

    fun encode(): Map<String, String> = mapOf(
        KEY_MODE to mode.wire,
        KEY_THRESHOLD to threshold.toString(),
        KEY_FOLLOW to followSystem.toString(),
        KEY_AUTO_DOWNLOAD to autoDownload.toString(),
        KEY_VIDEO_PRELOAD to videoPreload.toString(),
        KEY_BACKGROUND to backgroundConnection.toString(),
        KEY_PROMPT_SHOWN to promptShown.toString(),
    )

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_FOLLOW = "followSystem"
        const val KEY_AUTO_DOWNLOAD = "autoDownload"
        const val KEY_VIDEO_PRELOAD = "videoPreload"
        const val KEY_BACKGROUND = "backgroundConnection"
        const val KEY_PROMPT_SHOWN = "promptShown"

        /** 每个键独立兜底，一个坏了不连累其它；阈值读回后夹紧。 */
        fun decode(get: (String) -> String?): PowerSavingPrefs {
            val d = PowerSavingPrefs()
            return PowerSavingPrefs(
                mode = PowerSaveMode.fromWire(get(KEY_MODE)),
                threshold = get(KEY_THRESHOLD)?.toIntOrNull() ?: d.threshold,
                followSystem = get(KEY_FOLLOW)?.toBooleanStrictOrNull() ?: d.followSystem,
                autoDownload = get(KEY_AUTO_DOWNLOAD)?.toBooleanStrictOrNull() ?: d.autoDownload,
                videoPreload = get(KEY_VIDEO_PRELOAD)?.toBooleanStrictOrNull() ?: d.videoPreload,
                backgroundConnection = get(KEY_BACKGROUND)?.toBooleanStrictOrNull() ?: d.backgroundConnection,
                promptShown = get(KEY_PROMPT_SHOWN)?.toBooleanStrictOrNull() ?: d.promptShown,
            ).clamped()
        }
    }
}

/** 电池 / 系统省电的读数（null = 未知）。 */
data class BatteryReading(
    val level: Int? = null,
    val charging: Boolean? = null,
    val systemSaver: Boolean? = null,
)

/**
 * 此刻耗电项的**生效值**：`effective = userValue && !active`（§3）。
 * 退出省电自然回到用户原值，没有「恢复」这一步。
 * [backgroundConnection] 为 true 表示「后台保持连接」生效（不主动断开）。
 */
data class PowerSaveStatus(
    val active: Boolean = false,
    val reason: PowerSaveReason? = null,
    val animations: Boolean = true,
    val autoDownload: Boolean = true,
    val videoPreload: Boolean = true,
    val backgroundConnection: Boolean = true,
    /** 状态行「N 项已暂停」：生效时**用户值为开**的耗电项数（真正被省电暂停的），不是总数；未生效为 0。 */
    val pausedCount: Int = 0,
) {
    companion object {

        /** 纯函数：偏好 + 动画偏好 + 电池读数 → 状态。 */
        fun of(
            prefs: PowerSavingPrefs,
            animationsPref: Boolean,
            battery: BatteryReading,
            /** 推送不可达时「后台保持连接」本来就不生效，不算作被暂停的项。 */
            backgroundAvailable: Boolean = true,
        ): PowerSaveStatus {
            val reason = PowerSaveDecision.reason(
                PowerSaveContext(
                    mode = prefs.mode, threshold = prefs.threshold, level = battery.level,
                    charging = battery.charging, followSystem = prefs.followSystem, systemSaver = battery.systemSaver,
                ),
            )
            val active = reason != null
            return PowerSaveStatus(
                active = active,
                reason = reason,
                animations = animationsPref && !active,
                autoDownload = prefs.autoDownload && !active,
                videoPreload = prefs.videoPreload && !active,
                backgroundConnection = prefs.backgroundConnection && !active,
                pausedCount = if (active) {
                    listOf(animationsPref, prefs.autoDownload, prefs.videoPreload, prefs.backgroundConnection && backgroundAvailable).count { it }
                } else {
                    0
                },
            )
        }
    }
}
