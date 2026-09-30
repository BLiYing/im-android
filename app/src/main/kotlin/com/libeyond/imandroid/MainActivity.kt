package com.libeyond.imandroid

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.AppearancePrefs
import com.libeyond.imandroid.data.AppearanceStore
import com.libeyond.imandroid.ui.theme.IMAppearance
import com.libeyond.imandroid.ui.theme.IMThemeMode
import com.libeyond.imandroid.ui.theme.isDarkFor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.content.Intent
import com.libeyond.imandroid.data.AppActive
import com.libeyond.imandroid.data.reportAppState
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.data.NotificationRoute
import com.libeyond.imandroid.ui.AppIconSwitcher
import com.libeyond.imandroid.ui.AppRoot
import androidx.compose.runtime.CompositionLocalProvider
import com.libeyond.imandroid.ui.theme.IMAppTheme
import com.libeyond.imandroid.ui.theme.LocalMediaHost
import com.libeyond.imandroid.ui.theme.MediaHost
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(AppearanceStore.state.value.mode.let { m ->
            if (m == IMThemeMode.System) isSystemNight() else m == IMThemeMode.Dark
        })

        // API 33+ 由平台 LocaleManager 负责重建 Activity；更低版本没有 per-app locale，自己重建。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val started = LanguageStore.localeTag()
            lifecycleScope.launch {
                LanguageStore.pref.drop(1).collect { if (LanguageStore.localeTag() != started) recreate() }
            }
        }

        val client = (application as IMApp).client

        // 点系统推送通知冷启动/从后台唤起（M5 批次 2）：把 conv_id 记进 NotificationRoute，
        // 真正的消费（查会话/现造占位会话/设 openConv）在 ui/MainScreen.kt，这里只管"收到了"。
        handleNotificationIntent(intent)

        // 回到前台即唤醒连接。判据在 IMSocketManager.wake 里（已连接只探活、
        // manualClose 后不连），这里只管发信号。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                client.wake("foreground")
            }
        }

        // 通知判定 alertDecision 的 appActive 输入（NOTIFICATIONS_DESIGN §3.1）：本 App 只有一个
        // Activity，拿它的 RESUMED 区间当"前台"的代理——见 data/AppActive.kt 类注释。
        // 同一落点上报 app_state（PROTOCOL §6.12，M5 批次 2，见 data/AppStateReport.kt 类注释）：
        // 服务端就是靠这帧判断该不该给这条连接发离线推送（FCM）；未连接时静默丢帧没关系，
        // IMClient 里 socket 重新连上时会补发一次当前实际状态。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppActive.current = true
                client.socket.reportAppState(true)
                try {
                    awaitCancellation()
                } finally {
                    AppActive.current = false
                    client.socket.reportAppState(false)
                }
            }
        }

        // App 切后台即暂停语音（保留位点，回来接着听）：本 App 没有后台音频能力，
        // 不主动转成暂停的话回到前台气泡还显示「播放中」、进度却不动（iOS `handleEnterBackground:` 同理）。
        // 同一时机打断录音（§5.4）：切后台时录音机没有系统 AudioFocus 事件可依赖
        // （麦克风没被别人抢，只是本 App 自己不在前台了），必须在这里主动 interrupt。
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                client.voice.pause()
                client.recorder.interrupt()
                // 外观页选的桌面图标在这里才落地（前台切会被系统当场结束任务，见 AppIconSwitcher）
                AppIconSwitcher.commitPending(this@MainActivity)
            }
        })

        setContent {
            val prefs by AppearanceStore.state.collectAsState()
            // 强制浅/深色时系统栏图标也得跟着翻，不然「App 深色 + 系统浅色」时状态栏是黑字压黑底
            val dark = isDarkFor(prefs.mode)
            LaunchedEffect(dark) { applySystemBars(dark) }
            IMAppTheme(mode = prefs.mode, appearance = prefs.toAppearance(), chatTheme = prefs.theme) {
                CompositionLocalProvider(
                    LocalMediaHost provides MediaHost(client.host, BuildConfig.USE_TLS),
                ) {
                    AppRoot(client)
                }
            }
        }
    }

    /**
     * 已在运行（后台/前台）时点通知会走这里，而不是 [onCreate]——manifest 把本 Activity 设成
     * `launchMode="singleTask"`，系统据此复用现有实例并回调 `onNewIntent`，不会在任务栈里
     * 叠出第二个 MainActivity（同一个通知点两次、或点了通知又点桌面图标都不会开出重复页面）。
     * `setIntent` 是必要的一步：不写的话下次 `getIntent()`/`this.intent` 读到的还是旧 intent。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(EXTRA_NOTIFICATION_CONV_ID)
        if (convId.isNullOrBlank()) return
        val title = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE).orEmpty()
        NotificationRoute.request(convId, title)
    }

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * 系统栏图标明暗按 **App 的显示模式**判，而不是按系统（`enableEdgeToEdge()` 默认只看系统）。
     * 导航栏遮罩沿用 androidx 默认的两档半透明值，观感与改动前一致。
     */
    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK) { dark },
        )
    }

    companion object {
        private val NAV_SCRIM_LIGHT = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val NAV_SCRIM_DARK = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)

        /** [com.libeyond.imandroid.fcm.FcmMessagingService] 点击通知构造 intent 时写这两个 extra。 */
        const val EXTRA_NOTIFICATION_CONV_ID = "notification_conv_id"
        const val EXTRA_NOTIFICATION_TITLE = "notification_title"
    }
}

/** 偏好快照 → 主题层要的用户可调值。 */
private fun AppearancePrefs.toAppearance() = IMAppearance(
    chatFontSize = chatFontSize.sp,
    bubbleRadius = bubbleRadius.dp,
    wallpaper = wallpaper,
    animationsEnabled = animationsEnabled,
)
