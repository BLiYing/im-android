package com.libeyond.imandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.libeyond.imandroid.ui.AppRoot
import com.libeyond.imandroid.ui.theme.IMAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val client = (application as IMApp).client

        // 回到前台即唤醒连接。判据在 IMSocketManager.wake 里（已连接只探活、
        // manualClose 后不连），这里只管发信号。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                client.wake("foreground")
            }
        }

        setContent {
            IMAppTheme {
                AppRoot(client)
            }
        }
    }
}
