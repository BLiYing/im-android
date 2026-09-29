package com.libeyond.imandroid.data

import android.content.Context
import android.content.SharedPreferences
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观偏好的**唯一持有者**（对齐 iOS `IMAppearance.shared`）。
 *
 * 与 [LanguageStore] 同一手法：普通 SharedPreferences（不涉密）+ 一层 [StateFlow]。
 * `MainActivity` 订阅它喂给 `IMAppTheme`，于是改一下全 App 立刻重组——包括已经打开的聊天页
 * （iOS 靠 `IMAppearanceDidChangeNotification` 逐页刷新，本端靠 CompositionLocal 自然下发）。
 * 页面不得绕过这里直接读写存储（`UI_COLOR.md` §7.3）。
 *
 * 应用图标**不在这里**：它的真相源是系统的 activity-alias 启用状态（iOS 同理读
 * `alternateIconName`），见 `ui/AppIconSwitcher.kt`。
 */
object AppearanceStore {

    private const val PREFS = "im_appearance"

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(AppearancePrefs())

    val state: StateFlow<AppearancePrefs> = _state.asStateFlow()

    /** [com.libeyond.imandroid.IMApp.onCreate] 里调一次；与登录无关（外观是本机数据）。 */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.value = AppearancePrefs.decode { p.getString(it, null) }
    }

    /** 改一项：先夹紧再比较，值没变就不写盘、不下发（拖滑块时每一帧都会调进来）。 */
    fun update(transform: (AppearancePrefs) -> AppearancePrefs) {
        val before = _state.value
        val next = transform(before).clamped()
        if (next == before) return
        _state.value = next
        prefs?.edit()?.apply { next.encode().forEach { (k, v) -> putString(k, v) } }?.apply()
            ?: IMLog.tag("IM.Appearance").w("update_before_init")
    }

    /** iOS `resetToDefaults`（应用图标由调用方另行复位）。 */
    fun reset() = update { AppearancePrefs() }
}
