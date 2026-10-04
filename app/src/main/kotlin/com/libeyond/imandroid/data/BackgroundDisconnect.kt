package com.libeyond.imandroid.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §4.4「后台保持连接」：关闭或省电生效时，退后台 60 秒后主动断开长连接，靠 FCM 收提醒。
 * 前提（任一不满足就不断开）：推送可达 = 服务端 `fcm_enabled` 且本机本次登录的 FCM 令牌已上报成功。
 */
object BackgroundDisconnect {
    const val DELAY_MS = 60_000L

    /** 该策略是否生效（决定要不要在 onStop 起计时器、以及设置页那一行是否可用）。 */
    fun pushReachable(fcmEnabled: Boolean?, tokenReported: Boolean): Boolean = fcmEnabled == true && tokenReported

    /** 「后台保持连接」生效值为关（用户关了或省电生效）且推送可达。 */
    fun applies(keepConnectionEffective: Boolean, pushReachable: Boolean): Boolean =
        !keepConnectionEffective && pushReachable

    /** 计时到点能不能真的断：通话中、上传 / 下载中都不断（结束后重新计时）。 */
    fun canDisconnectNow(inCall: Boolean, activeTransfers: Int): Boolean = !inCall && activeTransfers <= 0
}

/**
 * 计时编排（**进程级**，由 IMApp 持有、用应用作用域；Activity 只转发 [onStart] / [onStop]——
 * Activity 重建或返回键退出都不影响计时与停靠状态）。
 *
 * [onStop] 起 60 秒计时，到点若 [applies] 且 [busy] 为否就 [disconnect]，忙则每 60 秒重判；
 * 后台期间条件变化（电量穿阈值、令牌上报成功…）调 [reevaluate]：已过 60 秒的就地重判。
 * [onStart] 取消计时并**无条件** [reconnect]（立即、不走退避；没停靠时对方是空操作）。
 * 依赖全部注入，JVM 单测可跑。
 */
class BackgroundConnectionKeeper(
    private val scope: CoroutineScope,
    private val applies: () -> Boolean,
    private val busy: () -> Boolean,
    private val disconnect: () -> Unit,
    private val reconnect: () -> Unit,
    private val delayMs: Long = BackgroundDisconnect.DELAY_MS,
) {
    private var job: Job? = null
    @Volatile private var background = false
    @Volatile private var elapsed = false
    @Volatile var parked: Boolean = false
        private set

    fun onStop() {
        job?.cancel()
        background = true
        elapsed = false
        job = scope.launch {
            delay(delayMs)
            elapsed = true
            retryLoop()
        }
    }

    fun onStart() {
        job?.cancel()
        job = null
        background = false
        elapsed = false
        parked = false
        reconnect()
    }

    /** 后台期间状态变了：已过 60 秒且还没停靠的，现在就判一次（之前不满足、现在满足的在这里补上）。 */
    fun reevaluate() {
        if (!background || !elapsed || parked) return
        job?.cancel()
        job = scope.launch { retryLoop() }
    }

    private suspend fun retryLoop() {
        while (true) {
            if (!applies()) return // 不适用：等 reevaluate
            if (!busy()) {
                disconnect()
                parked = true
                return
            }
            delay(delayMs)
        }
    }
}
