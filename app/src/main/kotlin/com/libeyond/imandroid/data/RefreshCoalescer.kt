package com.libeyond.imandroid.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 群帧触发会话列表重拉的合并窗口（毫秒）。 */
internal const val GROUP_LIST_REFRESH_MS = 300L

/**
 * 把一阵连发的「请重拉」请求合并成尾部去抖的一次：[request] 在 [windowMs] 内重复调用只跑一次 [action]；
 * [action] 在飞时又来请求，则在它结束后补一轮（在途那次可能早于刚发生的写）。
 *
 * 为什么要有：群邀请 100 人曾逐人推 100 帧 `group`，每帧都 `refreshConversations()`，
 * 100 次并发 `GET /conversations` + 落库把主流程拖到分钟级。
 */
internal class RefreshCoalescer(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val action: suspend () -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var dirty = false

    fun request() {
        synchronized(lock) {
            if (running) {
                dirty = true
                return
            }
            running = true
        }
        scope.launch { drain() }
    }

    /** 「判空 + 摘掉 running」在同一把锁里：否则收尾瞬间来的请求会被当成「在飞」而丢掉。 */
    private suspend fun drain() {
        var finished = false
        try {
            delay(windowMs)
            do {
                synchronized(lock) { dirty = false }
                action()
                val again = synchronized(lock) { if (dirty) true else { running = false; finished = true; false } }
            } while (again)
        } finally {
            // 被取消 / action 抛出时也要摘掉，否则之后的请求永远被当成「在飞」
            if (!finished) synchronized(lock) { running = false; dirty = false }
        }
    }
}
