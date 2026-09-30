package com.libeyond.imandroid.data

/**
 * 通知与提示音设置——纯模型（`../../IMServer/docs/design/NOTIFICATIONS_DESIGN.md` §6/§7）。
 *
 * **不 import `android.*`**（CODING_STYLE §二）：存储与 StateFlow 包装在 [NotificationSettingsStore]，
 * 这里只有可以直接单测的纯数据与纯函数。
 *
 * 字段集合三端一致：`private.{enabled,preview,sound}`、`group.{enabled,preview,sound}`、
 * `inApp.{sound,vibrate,preview}`、`badge.includeMuted`（§6）。[desktop] 只为让
 * [AlertDecision] 在本端也能跑桌面/浏览器向量（`alert_decision.json` 里那两种 `platform`）——
 * **Android 不持久化它、也不出对应设置页**（任务范围：桌面字段允许在 Android 端省略持久化）。
 */
data class NotificationSettings(
    val private: NotifTypeSettings = NotifTypeSettings(),
    val group: NotifTypeSettings = NotifTypeSettings(),
    val inApp: InAppSettings = InAppSettings(),
    val badge: BadgeSettings = BadgeSettings(),
    val desktop: DesktopSettings = DesktopSettings(),
) {
    companion object {
        val DEFAULT = NotificationSettings()
    }
}

/** 私聊 / 群聊一类会话的通知设置（§2.3）。默认全开（§3.7，§9 第 7 问已拍板）。 */
data class NotifTypeSettings(
    val enabled: Boolean = true,
    val preview: Boolean = true,
    val sound: NotifSound = NotifSound.DEFAULT,
)

/** 应用内通知（§2.2 第二组）。[preview] 是 P1（应用内横幅），P0 只存默认值，不接实际横幅。 */
// 默认全关（2026-09-29 用户：App 开着时没必要响/振/弹；提醒留给后台时的系统通知）
data class InAppSettings(
    val sound: Boolean = false,
    val vibrate: Boolean = false,
    val preview: Boolean = false,
)

/** 角标计数（§3.4）。默认关 = 现行口径（免打扰不计）。 */
data class BadgeSettings(val includeMuted: Boolean = false)

/** 桌面通知（§2.5，Web/Electron 专用）。Android 只为跑通 [AlertDecision] 的桌面向量而携带，不持久化。 */
data class DesktopSettings(
    val enabled: Boolean = true,
    val sound: Boolean = true,
    val volume: Int = 7,
)

/** 提示音 id（§5）。未知 id 一律回落 [DEFAULT]——存的是 id 不是文件名，防止老版本存的新 id 在旧版本上炸。 */
enum class NotifSound(val wire: String) {
    NONE("none"),
    DEFAULT("default"),
    CHORD("chord"),
    CHIME("chime"),
    RISE("rise"),
    DROP("drop"),
    ;

    companion object {
        fun fromWire(wire: String?): NotifSound = entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/** 改私聊或群聊那一类的设置，返回整份新值——调用方不用自己写 `if (group) copy(group=...) else copy(private=...)`。 */
fun NotificationSettings.withType(group: Boolean, transform: (NotifTypeSettings) -> NotifTypeSettings): NotificationSettings =
    if (group) copy(group = transform(this.group)) else copy(private = transform(this.private))

/** 取私聊或群聊那一类当前设置（[withType] 的只读对偶）。 */
fun NotificationSettings.typeOf(group: Boolean): NotifTypeSettings = if (group) this.group else this.private

/**
 * 三项**账号级**通知设置（M5，`../../IMServer/docs/PROTOCOL.md` §6.13）：私聊/群聊
 * `{enabled,preview,sound}` 与 `badge.include_muted`。挪到账号级、多端同步——同一账号换设备登录、
 * 或在多台设备上改，看到的是同一份。[InAppSettings]（应用内声音/振动/横幅）与 [DesktopSettings]
 * **仍是每台设备自己的**，不在这份里（见 [NotificationSettings] 类注释、`AccountNotifySettingsStore`）。
 *
 * **同一套字段与迁移口径的另外两端**：iOS `IMNotificationSettings`、Web `notifySettings.ts`——
 * 三端各自按协议独立实现，行为必须一致，改这里要一并核对那两处。
 */
data class AccountNotifyFields(
    val private: NotifTypeSettings = NotifTypeSettings(),
    val group: NotifTypeSettings = NotifTypeSettings(),
    val badge: BadgeSettings = BadgeSettings(),
)

/** 从整份本地设置里取出账号级那三项（PUT 之前、或对比"改没改"时用）。 */
fun NotificationSettings.accountFields(): AccountNotifyFields = AccountNotifyFields(private, group, badge)

/** 把账号级那三项覆盖进整份本地设置——[InAppSettings]/[DesktopSettings] 原样保留。 */
fun NotificationSettings.withAccountFields(f: AccountNotifyFields): NotificationSettings =
    copy(private = f.private, group = f.group, badge = f.badge)

/**
 * SharedPreferences 扁平键 ↔ 模型的编解码（**每个键独立回落默认值**，NOTIFICATIONS_DESIGN §6：
 * 「读到非法值一律回落默认」）——单个键存坏或缺失不该拖累其它字段，[decode] 逐键各自兜底，
 * 不是把整份 JSON 一次性反序列化（那样一个字段坏了整份都得回落默认）。
 *
 * 只编解码 Android 会持久化的那部分（不含 [DesktopSettings]，见 [NotificationSettings] 类注释）。
 */
object NotificationSettingsCodec {
    private const val K_PRIVATE_ENABLED = "private.enabled"
    private const val K_PRIVATE_PREVIEW = "private.preview"
    private const val K_PRIVATE_SOUND = "private.sound"
    private const val K_GROUP_ENABLED = "group.enabled"
    private const val K_GROUP_PREVIEW = "group.preview"
    private const val K_GROUP_SOUND = "group.sound"
    private const val K_INAPP_SOUND = "inApp.sound"
    private const val K_INAPP_VIBRATE = "inApp.vibrate"
    private const val K_INAPP_PREVIEW = "inApp.preview"
    private const val K_BADGE_INCLUDE_MUTED = "badge.includeMuted"

    /** [NotificationSettingsStore] 只需要读这些键；集中在这一处，别处不必知道键名字面量。 */
    val KEYS: List<String> = listOf(
        K_PRIVATE_ENABLED, K_PRIVATE_PREVIEW, K_PRIVATE_SOUND,
        K_GROUP_ENABLED, K_GROUP_PREVIEW, K_GROUP_SOUND,
        K_INAPP_SOUND, K_INAPP_VIBRATE, K_INAPP_PREVIEW,
        K_BADGE_INCLUDE_MUTED,
    )

    fun encode(s: NotificationSettings): Map<String, String> = mapOf(
        K_PRIVATE_ENABLED to s.private.enabled.toString(),
        K_PRIVATE_PREVIEW to s.private.preview.toString(),
        K_PRIVATE_SOUND to s.private.sound.wire,
        K_GROUP_ENABLED to s.group.enabled.toString(),
        K_GROUP_PREVIEW to s.group.preview.toString(),
        K_GROUP_SOUND to s.group.sound.wire,
        K_INAPP_SOUND to s.inApp.sound.toString(),
        K_INAPP_VIBRATE to s.inApp.vibrate.toString(),
        K_INAPP_PREVIEW to s.inApp.preview.toString(),
        K_BADGE_INCLUDE_MUTED to s.badge.includeMuted.toString(),
    )

    fun decode(raw: Map<String, String?>): NotificationSettings {
        val d = NotificationSettings.DEFAULT
        fun bool(key: String, fallback: Boolean): Boolean = raw[key]?.toBooleanStrictOrNull() ?: fallback
        return NotificationSettings(
            private = NotifTypeSettings(
                enabled = bool(K_PRIVATE_ENABLED, d.private.enabled),
                preview = bool(K_PRIVATE_PREVIEW, d.private.preview),
                sound = NotifSound.fromWire(raw[K_PRIVATE_SOUND]),
            ),
            group = NotifTypeSettings(
                enabled = bool(K_GROUP_ENABLED, d.group.enabled),
                preview = bool(K_GROUP_PREVIEW, d.group.preview),
                sound = NotifSound.fromWire(raw[K_GROUP_SOUND]),
            ),
            inApp = InAppSettings(
                sound = bool(K_INAPP_SOUND, d.inApp.sound),
                vibrate = bool(K_INAPP_VIBRATE, d.inApp.vibrate),
                preview = bool(K_INAPP_PREVIEW, d.inApp.preview),
            ),
            badge = BadgeSettings(includeMuted = bool(K_BADGE_INCLUDE_MUTED, d.badge.includeMuted)),
            // desktop 恒为默认——Android 不持久化它（类注释）
        )
    }
}
