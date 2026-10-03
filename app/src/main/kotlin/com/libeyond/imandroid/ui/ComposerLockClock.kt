package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 「现在」，在最近一个定时禁言到期的那一刻刷新一次——输入栏锁据此自己解开。
 * 服务端到期不推帧、组合期也没有别的东西触发重组，不排这一下的话，定时禁言结束后输入栏会一直锁着，
 * 直到用户退出重进或碰巧来了群帧/新消息（/code-review）。[untils]：各 `mute_until`，`<= 0`（没禁/永久）与已过期的不参与。
 */
@Composable
fun rememberLockNow(vararg untils: Long): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val next = untils.filter { it > System.currentTimeMillis() }.minOrNull()
    LaunchedEffect(next) {
        now = System.currentTimeMillis()
        if (next != null) {
            delay(next - now + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}
