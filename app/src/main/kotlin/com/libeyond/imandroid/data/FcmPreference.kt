package com.libeyond.imandroid.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「接收离线推送」开关的本地持久化——**本设备独立**，不随账号同步（同 `PUSH_M5_DESIGN.md` §5 iOS 那颗
 * 开关的语义："接收离线推送（本设备）"）。默认开，与本仓其余通知类开关的出厂默认口径一致
 * （NOTIFICATIONS_DESIGN §3.7：默认全开）。写法镜像 [LanguageStore]/[NotificationSettingsStore]。
 */
object FcmPreference {
    private const val PREFS = "im_fcm"
    private const val KEY_ENABLED = "enabled"

    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** [com.libeyond.imandroid.IMApp.onCreate] 里调一次；不必等登录——本设备偏好与账号无关。 */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(v: Boolean) {
        _enabled.value = v
        prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply()
    }
}
