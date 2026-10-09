package com.libeyond.imandroid.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * `send_msg` 的 ack 超时（对齐 iOS `IMSocketManager.handleAckTimeout`）：
 * 每 [intervalMs] 没等到 ack 就用**同一个 client_msg_id** 重发一次（服务端 `(conv_id, client_msg_id)` 唯一索引幂等去重），
 * 重发 [maxResend] 次仍无回应再判失败——总计约 20s（5s × (1 + 3)）。
 *
 * 没有这一层，断网 / ack 丢失时待发气泡会一直挂着时钟、永远出不来红点。
 * 同一条再次 [arm]（重连补发 / 用户点重发）会顶掉旧计时并把重发次数清零，不让旧的提前判死刚补发的那一轮。
 * 判失败后重连不再自动补发（`inFlight` 只含 Sending）——要用户点红点重发，与 iOS 一致。
 *
 * [resend] / [fail] 由调用方保证「只对仍是 Sending 的行生效」；计时本身不碰库。
 */
internal class SendAckTimeouts(
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxResend: Int = DEFAULT_MAX_RESEND,
    private val resend: suspend (clientMsgId: String) -> Unit,
    private val fail: suspend (clientMsgId: String) -> Unit,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun arm(clientMsgId: String) {
        // LAZY + 原子换位：并发 arm 同一条时旧计时必被顶掉，不会漏下一个取消不到的孤儿
        val job = scope.launch(start = CoroutineStart.LAZY) {
            repeat(maxResend) {
                delay(intervalMs)
                resend(clientMsgId)
            }
            delay(intervalMs)
            fail(clientMsgId)
        }
        jobs.put(clientMsgId, job)?.cancel()
        job.invokeOnCompletion { jobs.remove(clientMsgId, job) }
        job.start()
    }

    /** ack / 拒收已到：这条不需要计时了，别让它白等再去查库。 */
    fun cancel(clientMsgId: String) {
        jobs.remove(clientMsgId)?.cancel()
    }

    companion object {
        /** iOS `kIMAckTimeout`。 */
        const val DEFAULT_INTERVAL_MS = 5_000L

        /** iOS `kIMMaxResend`。 */
        const val DEFAULT_MAX_RESEND = 3
    }
}
