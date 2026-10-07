package com.libeyond.imandroid.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 删掉的恰好是会话列表摘要指着的那一条 → 重拉会话列表（2026-10-07 OPPO 实测：为所有人删除最后一条后，
 * 列表仍显示被删原文，直到重启 / 重连）。本地删消息不动会话行，摘要只能等服务端那份——服务端
 * `lastVisible` 已跳过「为所有人删除」与「本人仅为我删除」的行。
 * 对齐 iOS（移除通知 → 0.4s HTTP reload）与 Web（`onMessageRemoved` → `scheduleListRefresh`）。
 *
 * ⚠️ 只由**删除帧**（`msg_op delete` / `msg_hidden`，含本人操作的回声）触发，**不挂进 `removeMessages`**：
 * 会话列表刷新后的 [catchUpHidden] 也调它，挂进去就是 iOS 踩过的那个自激刷新环。
 */
object PreviewRefresh {
    fun needed(lastConvSeq: Long, removed: Collection<Long>): Boolean = lastConvSeq > 0 && lastConvSeq in removed
}

/** 合并：[WINDOW_MS] 内的多次请求（重连后的积压删除帧、连删几条）只拉一次。 */
internal class PreviewRefresher(private val scope: CoroutineScope, private val refresh: suspend () -> Unit) {
    private var job: Job? = null

    @Synchronized
    fun request() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(WINDOW_MS)
            refresh()
        }
    }

    private companion object {
        const val WINDOW_MS = 300L
    }
}

/** 删除帧落库后调：摘要指着被删的那条才重拉。 */
internal suspend fun MessageService.refreshIfPreviewRemoved(owner: String, convId: String, removed: Collection<Long>) {
    val last = repo.conversations.byId(owner, convId)?.lastConvSeq ?: return
    if (PreviewRefresh.needed(last, removed)) previewRefresher.request()
}
