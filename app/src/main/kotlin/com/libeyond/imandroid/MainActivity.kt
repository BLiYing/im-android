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
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.ui.AppIconSwitcher
import com.libeyond.imandroid.ui.AppRoot
import androidx.compose.runtime.CompositionLocalProvider
import com.libeyond.imandroid.ui.theme.IMAppTheme
import com.libeyond.imandroid.ui.theme.LocalMediaHost
import com.libeyond.imandroid.ui.theme.MediaHost
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

        // 回到前台即唤醒连接。判据在 IMSocketManager.wake 里（已连接只探活、
        // manualClose 后不连），这里只管发信号。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                client.wake("foreground")
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

    private companion object {
        val NAV_SCRIM_LIGHT = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val NAV_SCRIM_DARK = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}

/** 偏好快照 → 主题层要的用户可调值。 */
private fun AppearancePrefs.toAppearance() = IMAppearance(
    chatFontSize = chatFontSize.sp,
    bubbleRadius = bubbleRadius.dp,
    wallpaper = wallpaper,
    animationsEnabled = animationsEnabled,
)
