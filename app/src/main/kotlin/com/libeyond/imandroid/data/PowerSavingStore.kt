package com.libeyond.imandroid.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.PowerManager
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 省电模式的**唯一持有者**（POWER_SAVING_DESIGN；写法同 [AppearanceStore]）：本机偏好 + 电池 / 系统省电读数
 * → [status]（耗电项生效值）。电量来自 `ACTION_BATTERY_CHANGED` 粘性广播，系统省电来自
 * `PowerManager.isPowerSaveMode` + `ACTION_POWER_SAVE_MODE_CHANGED`，都不需要权限。
 *
 * 「界面动画」与外观页同一个值：读 [AppearanceStore]，生效值由 [status] 的 `animations` 给出
 * （`MainActivity` 组装 appearance 处取它）。
 */
object PowerSavingStore {

    private const val PREFS = "im_power_saving"
    private val log = IMLog.tag("IM.PowerSaving")

    private var prefs: SharedPreferences? = null
    private var power: PowerManager? = null

    private val _prefs = MutableStateFlow(PowerSavingPrefs())
    val state: StateFlow<PowerSavingPrefs> = _prefs.asStateFlow()

    private val _battery = MutableStateFlow(BatteryReading())
    val battery: StateFlow<BatteryReading> = _battery.asStateFlow()

    private val _status = MutableStateFlow(PowerSaveStatus())
    val status: StateFlow<PowerSaveStatus> = _status.asStateFlow()

    /** §5 自动开启提示：非 null = 该弹一次 Toast（值 = 当时的阈值百分比），UI 弹完调 [consumeAutoToast]。 */
    private val _autoToast = MutableStateFlow<Int?>(null)
    val autoToast: StateFlow<Int?> = _autoToast.asStateFlow()
    private var promptState = PowerSavePromptState()

    /** 服务端 `server-config.fcm_enabled`；null = 还没拉到 / 拉不到。 */
    private val _fcmEnabled = MutableStateFlow<Boolean?>(null)
    val fcmEnabled: StateFlow<Boolean?> = _fcmEnabled.asStateFlow()

    private val _tokenReported = MutableStateFlow(false)

    fun setFcmEnabled(v: Boolean?) { _fcmEnabled.value = v; recompute() }

    /** 本机本次登录的 FCM 令牌是否已上报成功（IMApp 订阅 `fcmTokenStore.reportedToken` 喂进来）。 */
    fun setTokenReported(v: Boolean) { _tokenReported.value = v; recompute() }

    /** 推送可达（§4.4 前提）：服务端 `fcm_enabled` 且令牌已上报。 */
    fun pushReachable(): Boolean = BackgroundDisconnect.pushReachable(_fcmEnabled.value, _tokenReported.value)

    /** [com.libeyond.imandroid.IMApp.onCreate] 里调一次；与登录无关（本机数据，退出登录保留）。 */
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        _prefs.value = PowerSavingPrefs.decode { p.getString(it, null) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onBroadcast(intent)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // 粘性广播：注册即返回当前电量，不用等下一次变化
        val sticky = runCatching { app.registerReceiver(receiver, filter) }
            .onFailure { log.w("register_failed", "err" to it.javaClass.simpleName) }
            .getOrNull()
        // 首次读数：先定「本周期已提示」是否沿用（进程重启不重复弹，但已充电 / 不再触发的算新周期），再正常重算
        var b = BatteryReading(systemSaver = power?.isPowerSaveMode)
        if (sticky != null) b = b.copy(level = levelOf(sticky), charging = chargingOf(sticky))
        _battery.value = b
        val first = PowerSaveStatus.of(_prefs.value, AppearanceStore.state.value.animationsEnabled, b)
        val shown = PowerSavePrompt.startupShown(_prefs.value.promptShown, first.reason)
        promptState = PowerSavePromptState(shown = shown)
        persistShown(shown)
        recompute()
    }

    private fun onBroadcast(intent: Intent) {
        var b = _battery.value
        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            b = b.copy(level = levelOf(intent), charging = chargingOf(intent))
        }
        // 每次广播都顺手重读系统省电（两个 action 共用）
        b = b.copy(systemSaver = power?.isPowerSaveMode)
        _battery.value = b
        recompute()
    }

    /** 改一项：先夹紧再比较，值没变就不写盘（拖滑块每一帧都会进来）。 */
    fun update(transform: (PowerSavingPrefs) -> PowerSavingPrefs) {
        val before = _prefs.value
        val next = transform(before).clamped()
        if (next == before) return
        _prefs.value = next
        prefs?.edit()?.apply { next.encode().forEach { (k, v) -> putString(k, v) } }?.apply()
            ?: log.w("update_before_init")
        recompute()
    }

    /** 外观页「动画」变了也要重算（生效值 = 偏好 && !active）。 */
    fun recompute() {
        val animPref = AppearanceStore.state.value.animationsEnabled
        val s = PowerSaveStatus.of(_prefs.value, animPref, _battery.value, BackgroundDisconnect.pushReachable(_fcmEnabled.value, _tokenReported.value))
        if (s != _status.value) _status.value = s
        val step = PowerSavePrompt.onStatus(
            promptState, s.active, s.reason, _battery.value.charging, AppActive.current, System.currentTimeMillis(),
        )
        promptState = step.state
        persistShown(step.state.shown)
        if (step.showNow) _autoToast.value = _prefs.value.threshold
    }

    /** 「本周期已提示」落盘（不走 [update]：它会再触发 [recompute]）。 */
    private fun persistShown(v: Boolean) {
        val cur = _prefs.value
        if (cur.promptShown == v) return
        _prefs.value = cur.copy(promptShown = v)
        prefs?.edit()?.putString(PowerSavingPrefs.KEY_PROMPT_SHOWN, v.toString())?.apply()
    }

    /** 回前台（RESUMED）：后台排队的提示 10 分钟内补弹。 */
    fun onForeground() {
        val step = PowerSavePrompt.onForeground(promptState, _status.value.active, System.currentTimeMillis())
        promptState = step.state
        if (step.showNow) _autoToast.value = _prefs.value.threshold
    }

    fun consumeAutoToast() { _autoToast.value = null }

    private fun levelOf(i: Intent): Int? {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
    }

    /** plugged 非 0 即在充（含充满仍插着）；读不到该字段才算未知。 */
    private fun chargingOf(i: Intent): Boolean? =
        if (i.hasExtra(BatteryManager.EXTRA_PLUGGED)) i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 else null
}
