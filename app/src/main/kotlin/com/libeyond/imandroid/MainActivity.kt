package com.libeyond.imandroid

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.libeyond.imandroid.data.LanguageStore
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
        enableEdgeToEdge()

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
            }
        })

        setContent {
            IMAppTheme {
                CompositionLocalProvider(
                    LocalMediaHost provides MediaHost(client.host, BuildConfig.USE_TLS),
                ) {
                    AppRoot(client)
                }
            }
        }
    }
}
