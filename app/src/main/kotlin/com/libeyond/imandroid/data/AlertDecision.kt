package com.libeyond.imandroid.data

/**
 * 通知判定 `alertDecision` 的输入上下文（NOTIFICATIONS_DESIGN §3.1）。
 *
 * 字段名与共用向量 `IMServer/docs/conformance/alert_decision.json` 逐个对应
 * （`AlertDecisionTest` 直接读那份 JSON）。三端同名字段，取值口径见向量文件顶部的 `_doc`。
 */
data class AlertContext(
    /** `"mobile"` | `"desktop"` | `"browser"`。 */
    val platform: String,
    /** 只对实时新消息判；历史回填 / 离线积压 / 窗口加载一律不判（§3.1）。 */
    val isLive: Boolean,
    val isSelf: Boolean,
    val isSystem: Boolean,
    val isRecalled: Boolean,
    val isCallRecord: Boolean,
    /** 仅 [isCallRecord] 为真时有意义：这通未接来电的被叫是不是我。 */
    val missedCallForMe: Boolean,
    /** `"private"` | `"group"`。 */
    val convType: String,
    val muted: Boolean,
    /** 含 @全体。穿透免打扰。 */
    val mentionsMe: Boolean,
    /** 移动端：App 是否前台。桌面/浏览器不读这个字段（读 [windowFocused]）。 */
    val appActive: Boolean,
    /** 桌面/浏览器：窗口是否在焦点。移动端不读这个字段。 */
    val windowFocused: Boolean,
    /** 调用方判定的"用户此刻真的在看这个会话"（移动端 = App 前台且在该会话页）。 */
    val viewingConv: Boolean,
    /** 正在音视频通话中。 */
    val inCall: Boolean,
    val nowMs: Long,
    /** 上一次真正响过的时间戳；节流窗口 1500ms 内视为刚响过。 */
    val lastSoundAtMs: Long,
    val settings: NotificationSettings,
)

/** 判定结果。`soundId` 在 [sound] 为假时恒为 null。`banner` P0 恒为 false（P1 才做应用内横幅）。 */
data class AlertResult(
    val sound: Boolean,
    val vibrate: Boolean,
    val banner: Boolean,
    val osNotify: Boolean,
    val soundId: String?,
) {
    companion object {
        val SILENT = AlertResult(sound = false, vibrate = false, banner = false, osNotify = false, soundId = null)
    }
}

/**
 * 通知判定——三端同名纯函数（NOTIFICATIONS_DESIGN §3.1）。
 *
 * **平台分支都在这里实现**（不是只做移动端）：`alert_decision.json` 的 30 条向量里桌面/浏览器
 * 各占几条，本端虽然只在移动端真正调用它，但判定本身是三端共用的一份逻辑，写全了才谈得上
 * "改规则先改向量、三端一起改"——只实现 mobile 分支的话，这份文件就不再是那份共用逻辑的忠实拷贝。
 */
object AlertDecision {

    private const val THROTTLE_MS = 1500L
    private const val PLATFORM_MOBILE = "mobile"
    private const val PLATFORM_DESKTOP = "desktop"
    private const val CONV_GROUP = "group"

    fun decide(ctx: AlertContext): AlertResult {
        // ① 这几类**从不**提醒（§3.1 表格第一行 + 撤回 + 非我未接的通话记录）
        if (!ctx.isLive || ctx.isSelf || ctx.isSystem || ctx.isRecalled) return AlertResult.SILENT
        if (ctx.isCallRecord && !ctx.missedCallForMe) return AlertResult.SILENT
        // ② 正在看这个会话 / 正在通话：什么都不做
        if (ctx.viewingConv || ctx.inCall) return AlertResult.SILENT

        val typeSettings = if (ctx.convType == CONV_GROUP) ctx.settings.group else ctx.settings.private
        if (!typeSettings.enabled) return AlertResult.SILENT
        // ③ 免打扰且未 @我 → 不提醒；@我（含 @全体）穿透
        if (ctx.muted && !ctx.mentionsMe) return AlertResult.SILENT
        // ④ 移动端没有推送：App 不在前台就没有任何提醒渠道（P0，见 §0 结论）
        if (ctx.platform == PLATFORM_MOBILE && !ctx.appActive) return AlertResult.SILENT

        // ⑤ 节流：1.5 秒内响过就都不响/不振（连发十条只响一声）
        // 差值为负 = 系统时钟往回拨过：当没响过，否则回拨多久就静音多久（/code-review 2026-09-29，三端同改）
        val sinceLast = ctx.nowMs - ctx.lastSoundAtMs
        val throttled = sinceLast in 0 until THROTTLE_MS
        val soundAllowed = typeSettings.sound != NotifSound.NONE && !throttled
        val resolvedSoundId = typeSettings.sound.wire

        return when (ctx.platform) {
            PLATFORM_MOBILE -> {
                val sound = ctx.settings.inApp.sound && soundAllowed
                val vibrate = ctx.settings.inApp.vibrate && !throttled
                AlertResult(sound, vibrate, banner = false, osNotify = false, soundId = resolvedSoundId.takeIf { sound })
            }
            PLATFORM_DESKTOP -> {
                val sound = ctx.settings.desktop.sound && soundAllowed
                val osNotify = ctx.settings.desktop.enabled && !ctx.windowFocused
                AlertResult(sound, vibrate = false, banner = false, osNotify = osNotify, soundId = resolvedSoundId.takeIf { sound })
            }
            // 浏览器：只响，P0 不做 Web Notification（§2.5）
            else -> {
                val sound = ctx.settings.desktop.sound && soundAllowed
                AlertResult(sound, vibrate = false, banner = false, osNotify = false, soundId = resolvedSoundId.takeIf { sound })
            }
        }
    }
}
