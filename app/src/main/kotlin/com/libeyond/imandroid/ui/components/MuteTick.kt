package com.libeyond.imandroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.libeyond.imandroid.data.MuteState
import com.libeyond.imandroid.data.db.ConversationEntity
import kotlinx.coroutines.delay

/**
 * 定时免打扰到期刷新（NOTIFICATIONS_P1_DESIGN §4.4）：算出这一份会话表里「最近的一个 mute_until」，
 * 挂一个精确定时器，到点把返回值推进——读它的地方（会话列表铃铛/未读徽标、页签角标、例外列表）
 * 跟着重组，铃铛消失、未读重新计入，**不需要服务端推帧**（同 `online_until` 的到期口径）。
 * 前台回来也刷一遍（`repeatOnLifecycle(RESUMED)`）：后台期间系统可能冻结了协程调度，定时器不一定
 * 准时跑到点。
 *
 * 不在这里持有会话表本身——**纯展示不持业务状态**（CODING_STYLE §7②），调用方喂会话表进来；
 * 多个宿主各自调用时各自起一份定时器，代价很小（本仓会话表规模不大）。
 */
@Composable
fun rememberMuteTick(conversations: List<ConversationEntity>?): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val nextExpiry = remember(conversations, now) { MuteState.nearestFutureMuteUntil(conversations.orEmpty(), now) }
    LaunchedEffect(nextExpiry) {
        val target = nextExpiry ?: return@LaunchedEffect
        val delayMs = target - System.currentTimeMillis()
        if (delayMs > 0) delay(delayMs)
        now = System.currentTimeMillis()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            now = System.currentTimeMillis()
        }
    }
    return now
}
