package com.libeyond.imandroid.data

import android.content.Context
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知与提示音设置的唯一持有者（NOTIFICATIONS_DESIGN §6）：SharedPreferences `im_notifications`
 * 包一层 [MutableStateFlow]——设置页的当前值展示、判定层 [IncomingAlert] 的实时读取要读同一份，
 * 改了立刻两处都看得见。写法镜像 [LanguageStore]（同样是"设备本地、退出登录不清"的纯本地设置）。
 */
object NotificationSettingsStore {

    private const val PREFS = "im_notifications"

    private lateinit var prefs: android.content.SharedPreferences
    private val log = IMLog.tag("IM.Notif")
    private val _settings = MutableStateFlow(NotificationSettings.DEFAULT)

    /** 设置页与判定层共读这一份；未 [init] 前是默认值。 */
    val settings: StateFlow<NotificationSettings> = _settings.asStateFlow()

    /** 判定层用的同步读法（[IncomingAlert] 不在 Compose 里，用 `.value` 更直接）。 */
    val current: NotificationSettings get() = _settings.value

    /** [com.libeyond.imandroid.IMApp.onCreate] 里调一次；不必等登录——设备偏好与账号无关。 */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = NotificationSettingsCodec.decode(readAll())
    }

    fun update(next: NotificationSettings) {
        if (!::prefs.isInitialized) {
            log.w("update_before_init")
            return
        }
        _settings.value = next
        val editor = prefs.edit()
        NotificationSettingsCodec.encode(next).forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }

    /** 重置所有开关与提示音为默认值。**不碰任何会话的免打扰**（NOTIFICATIONS_DESIGN §3.6，与 Telegram 刻意不同）。 */
    fun reset() = update(NotificationSettings.DEFAULT)

    private fun readAll(): Map<String, String?> =
        NotificationSettingsCodec.KEYS.associateWith { prefs.getString(it, null) }
}
