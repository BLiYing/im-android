package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.WindowReqData
import com.libeyond.imandroid.sdk.protocol.WindowRespData
import com.libeyond.imandroid.sdk.ws.IMSocketManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「按锚点开窗」的请求方（`window_req` / `window_resp`，MESSAGE_WINDOW_DESIGN §3.2）。
 *
 * 从 `MessageService` 拆出（那个文件贴着 600 行硬闸）：它是**一问一答**的请求-应答，
 * 与那边"收帧就落库"的单向流是两种形状。
 *
 * **只在本地没有那条消息时才用**——本地有就直接开本地窗，没有理由为一个已经在库里的锚点
 * 去问服务端（§4）。收到的消息由 `MessageService` 落库，且**不推进同步游标**：
 * 窗口取数是一次性快照，推进游标会让 sync 以为这一段已经覆盖过了。
 */
internal class WindowRequester(
    private val socket: IMSocketManager,
    private val scope: CoroutineScope,
) {
    /**
     * **不做请求关联**：协议里 `window_resp` 不带请求 id，而同一时刻本端只会有一个会话在开窗，
     * 按 `conv_id` 认领足够了。replay=0：迟到的订阅者不该拿到上一次的窗口。
     */
    private val results = MutableSharedFlow<WindowRespData>(extraBufferCapacity = 8)

    /** 收到 `window_resp` 时由 `MessageService` 调。 */
    fun deliver(resp: WindowRespData) {
        scope.launch { results.emit(resp) }
    }

    fun request(convId: String, anchor: Long, before: Int, after: Int): Boolean =
        socket.send(
            FrameType.WINDOW_REQ,
            ProtocolJson.encodeToJsonElement(
                WindowReqData.serializer(), WindowReqData(convId, anchor, before, after),
            ),
        )

    /**
     * 发一次 `window_req` 并等应答。回 `null` = 没连上 / 超时。
     *
     * **先订阅再发帧**：反过来的话，应答比订阅早到就永远等不到了（经典的丢事件顺序坑）。
     */
    suspend fun await(
        convId: String,
        anchor: Long,
        before: Int,
        after: Int,
        timeoutMs: Long = 8_000L,
    ): WindowRespData? {
        val waiter = CompletableDeferred<WindowRespData>()
        val job = scope.launch { waiter.complete(results.first { it.convId == convId }) }
        if (!request(convId, anchor, before, after)) {
            job.cancel()
            return null // 没连上就别让调用方干等
        }
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            job.cancel()
        }
    }
}
