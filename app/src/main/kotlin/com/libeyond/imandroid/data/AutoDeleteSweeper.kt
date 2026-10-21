package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** 触发合并窗口：连续触发（列表刷新 + conv_update + 前台 tick 撞在一起）只扫一次。 */
internal const val AUTO_DELETE_COALESCE_MS = 300L

/**
 * 自动删除的「全量扫」（AUTO_DELETE_DESIGN §3.2）：把所有档位>0 的会话各清一遍（[purgeExpired]）。
 *
 * 触发方都只调 [request]：会话列表刷新 / `conv_update` / 本机改档位 / 启动·回前台 / 前台每 30 秒。
 * 走 [RefreshCoalescer] 合并去抖——幂等、可合并，别各自抢跑。后台不扫（没有后台任务），回前台补扫。
 * 清掉东西后经 [onPurged] 通知（置顶横幅 / 面板据此对齐）。
 */
internal class AutoDeleteSweeper(
    scope: CoroutineScope,
    private val repo: MessageRepository,
    private val ownerProvider: () -> String?,
    private val onPurged: (convId: String, seqs: List<Long>) -> Unit,
) {
    private val log = IMLog.tag("IM.AutoDelete")
    private val coalescer = RefreshCoalescer(scope, AUTO_DELETE_COALESCE_MS) { sweep() }

    fun request() = coalescer.request()

    /** 直接扫一遍（测试 / 合并器内部用）。库异常只记日志，不掀翻调用方。 */
    suspend fun sweep() {
        val owner = ownerProvider() ?: return
        try {
            val now = System.currentTimeMillis()
            for (conv in repo.conversations.withAutoDelete(owner)) {
                val seqs = repo.purgeExpired(owner, conv.convId, now)
                if (seqs.isNotEmpty()) onPurged(conv.convId, seqs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("auto_delete_sweep_failed", "err" to e.javaClass.simpleName)
        }
    }
}

/**
 * 本机改某会话的档位（AUTO_DELETE_DESIGN §3.3 C）：`PUT …/settings` 带 `auto_delete_sec`，成功后**立即**写库并清理。
 * 其余设置先 GET 当前值原样回传（整体替换接口；`mute_until` 省略 = 服务端保留）。失败（含无登录态）抛出，由调用方 toast + 回滚。
 */
suspend fun MessageService.setConvAutoDelete(convId: String, sec: Int) {
    val owner = ownerProvider() ?: error("no session owner") // 不能静默返回：页面会当成已保存
    val s = conversationsApi.settings(convId)
    conversationsApi.updateSettings(convId, s.pinnedAt, s.muted, s.markedUnread, autoDeleteSec = sec)
    repo.conversations.setAutoDeleteSec(owner, convId, sec)
    com.libeyond.imandroid.sdk.api.AutoDeleteFloor.set(convId, sec)
    autoDelete.sweep()
}
