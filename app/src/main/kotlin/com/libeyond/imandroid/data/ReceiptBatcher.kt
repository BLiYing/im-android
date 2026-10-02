package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `delivered` 回执的**合批**（OFFLINE_BACKLOG_DESIGN §4.8 C5）。
 *
 * 回执是**单调位点**（「我收到了该会话到第 N 条」），合批零语义损失：同一会话在窗口期内只留最大位点、
 * 窗口到了每个会话发一帧。离线积压补拉 10 万条时，逐条回执是 10 万个上行帧，服务端每帧还要做一次
 * 群快照 + 成员鉴权；合批后按页一帧（500 帧）。实时消息同走这里（一次发一串连续消息也只发一帧）。
 *
 * 对称兄弟：iOS `IMBacklogTracker queueReceiptForConv:upTo:` / `drainReceipts:` + `IMSocketManager sendReceiptForConv:upTo:`
 * （`kIMReceiptFlushDelay = 0.12s`）、Web `pendingReceipts` + `receiptTimer`（同 120ms）。
 * **window 路径不发 delivered**（翻历史不等于刚收到）——那是调用方的事，不在这里。
 *
 * 线程：[queue] 可在任意线程调；窗口到点后在 [scope] 里回调 [send]。
 */
class ReceiptBatcher(
    private val scope: CoroutineScope,
    private val send: (convId: String, upTo: Long) -> Unit,
    private val windowMs: Long = FLUSH_DELAY_MS,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Long>()
    private var scheduled = false

    /** 记一个位点；窗口内第一次调用才排定时器。`convId` 为空或 `upTo ≤ 0` 当无事发生。 */
    fun queue(convId: String, upTo: Long) {
        if (convId.isEmpty() || upTo <= 0) return
        synchronized(lock) {
            if (upTo > (pending[convId] ?: 0L)) pending[convId] = upTo
            if (scheduled) return
            scheduled = true
        }
        scope.launch {
            delay(windowMs)
            flush()
        }
    }

    /** 立刻把攒着的发出去（窗口到点、或断线前想尽量发出）。 */
    fun flush() {
        val batch = synchronized(lock) {
            scheduled = false
            val copy = LinkedHashMap(pending)
            pending.clear()
            copy
        }
        batch.forEach { (convId, upTo) -> send(convId, upTo) }
    }

    /** 丢掉攒着的（切账号 / 退出登录：别把上一个账号的回执发到下一个账号的连接上）。 */
    fun clear() = synchronized(lock) { pending.clear() }

    companion object {
        /** 与 iOS `kIMReceiptFlushDelay`、Web `receiptTimer` 同值。 */
        const val FLUSH_DELAY_MS = 120L
    }
}

/** 一页 sync 补拉该回多大的 `delivered` 位点。 */
object DeliveredUpTo {

    /**
     * 本页**别人发的**消息里最大的 `conv_seq`，且必须是真正落库成功的（`< firstFailedSeq`）；没有则 0（不回）。
     *
     * - 自己发的不回：回执是「我收到了对方的」；
     * - 落库失败的那一条及之后不回：回了就是宣称收到了其实没存下的东西，下次重拉才会补；
     * - `too_long` 页没有消息，天然得 0。
     */
    fun forSyncPage(messages: List<MessageData>, owner: String, firstFailedSeq: Long?): Long =
        messages.asSequence()
            .filter { it.from != owner && it.convSeq > 0 && (firstFailedSeq == null || it.convSeq < firstFailedSeq) }
            .maxOfOrNull { it.convSeq } ?: 0L
}
