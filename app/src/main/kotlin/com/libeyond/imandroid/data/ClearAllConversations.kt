package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ClearAllResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * 「清除所有对话」的编排与口径（IMServer `docs/design/CLEAR_ALL_CONVERSATIONS_DESIGN.md` §1/§4）。纯函数 + 顺序约束，便于单测。
 *
 * **顺序铁律（半清禁止）**：先请求，**请求成功后**才动本机；请求抛错则一步本机变更都不发生。
 * 本机变更按「位点（单事务）→ 缓存/通知」落地，位点取服务端 `items[].cleared_at`。
 */
object ClearAllConversations {

    /**
     * @param request `POST /users/me/conversations/clear`；抛错直接透出，[applyLocal]/[sideEffects] 不会被调用
     * @param applyLocal 对 items 逐会话「清除到位点」+ 移行（单事务）+ 丢 `HistoryFloors`
     * @param sideEffects 媒体缓存（不含头像）、通知栏、…… 失败不影响已完成的清除
     * @return 服务端回的 `cleared`（toast 用）
     */
    suspend fun run(
        request: suspend () -> ClearAllResult,
        applyLocal: suspend (ClearAllResult) -> Unit,
        sideEffects: suspend () -> Unit,
    ): Int {
        val result = request()
        applyLocal(result)
        try {
            sideEffects()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 位点已落库、会话已清；缓存/通知清理失败不回滚（下次进存储页可手清）
        }
        return result.cleared
    }
}
