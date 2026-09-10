package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.libeyond.imandroid.data.ChatScroll

/**
 * **只认轻点、把长按让给外层**的点击。气泡内部可点的小部件（文件行、引用块、下载徽标）用它代替 `clickable`。
 *
 * 为什么不能用 `clickable`：它在按下那一刻就**消费掉 down**，外层气泡的 `combinedClickable`
 * 等不到一个未消费的 down，长按永远不触发——「长按文件名没反应」（十七条对齐 #13），
 * 九宫格格子上的下载徽标也是同一个坑（2026-09-08 那次只在格子层绕开了）。
 *
 * 这里**不碰 down**，自己跟到抬起：没滑出 touchSlop、没按到长按时长，才在 Main 阶段消费 up 并回调。
 * Main 阶段由内向外传，子节点先拿到 up——消费掉之后外层的轻点就不会再触发一次；
 * 长按则因为按住时长超了，这里放行，外层照常弹菜单。判据与列表空白处的轻点同一个（[ChatScroll.isTap]）。
 */
fun Modifier.passThroughTap(enabled: Boolean = true, onTap: () -> Unit): Modifier {
    if (!enabled) return this
    return composed {
        // 调用方每次重组都会新建 lambda。拿它当 pointerInput 的 key，重组就会重启手势协程——
        // 下载进度每跳一次文件气泡就重组一次，正按着的那一下会凭空丢掉。所以 key 用 Unit、回调取最新值。
        val latest by rememberUpdatedState(onTap)
        Modifier
            // 丢了 clickable 也不能丢无障碍：读屏仍要知道这里能点
            .semantics {
                role = Role.Button
                onClick { latest(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var up: PointerInputChange? = null
                    while (up == null) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.size > 1) moved = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!change.pressed) up = change
                    }
                    val released = up ?: return@awaitEachGesture
                    // 外层已经把这次按下当成别的手势吃掉了（列表滚动、长按弹菜单后的抬起），不再算轻点
                    if (released.isConsumed) return@awaitEachGesture
                    val pressedMs = released.uptimeMillis - down.uptimeMillis
                    if (!ChatScroll.isTap(moved, pressedMs, viewConfiguration.longPressTimeoutMillis)) {
                        return@awaitEachGesture
                    }
                    released.consume()
                    latest()
                }
            }
    }
}
