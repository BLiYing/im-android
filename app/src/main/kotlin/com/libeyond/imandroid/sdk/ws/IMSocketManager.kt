package com.libeyond.imandroid.sdk.ws

import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.Envelope
import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 会话被服务端否定的两种原因，UI 据此给不同文案并回登录页。 */
enum class SessionEndReason { Revoked, Banned }

/**
 * WebSocket 长连接（对应 iOS `IMSocketManager`、Web `IMClient` 的连接部分）。
 *
 * ## 握手走请求头
 * `Authorization: Bearer <jwt>`，这是 PROTOCOL §1 的**首选**路径。
 * Web 用 `?token=` 是因为浏览器的 WebSocket API 设不了请求头——那是被迫，不是范式：
 * URI 里的凭据会被沿途反向代理 / 网关 / CDN 原样写进访问日志。OkHttp 能设头，别学。
 *
 * ## 收到未知 type 必须忽略、不崩
 * PROTOCOL §2 的硬约束。本类把帧原样发到 [frames]，解析交给上层按 type 分派；
 * 解析失败只记日志不抛。
 */
class IMSocketManager(
    private val scope: CoroutineScope,
    @Volatile var host: String,
    @Volatile var useTls: Boolean = false,
    /** 取当前 token；返回 null 时不连。 */
    private val tokenProvider: () -> String?,
) {
    private val log = IMLog.tag("IM.WS")

    private val ok = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // OkHttp 自己的 ping 不用——协议层有自己的 ping/pong（§1，25s），
        // 两套心跳并存只会让"连接是否活着"有两个互相矛盾的答案。
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    // replay = 0 但**改用 emit 而非 tryEmit**（见 onMessage）：无订阅者时 tryEmit 直接
    // 丢帧并返回 true，连日志都不打。P4 接同步后，sync_resp 若在收集器 attach 前到达
    // 就会被静默吞掉，客户端永远等不到响应且无法自愈。
    private val _frames = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)
    /** 所有下行帧。上层按 `type` 分派。 */
    val frames: SharedFlow<Envelope> = _frames.asSharedFlow()

    // replay = 1：UI 的收集器在首次组合时才订阅，而握手 401 可能在那之前就发生了
    // （冷启动 restore 成功 → connect → 服务端发现 sid 已吊销）。replay=0 会让这条事件
    // 永久丢失：本地凭据已被清空，UI 却停在主界面显示「已登录」，之后每个请求都 401。
    private val _sessionEnded = MutableSharedFlow<SessionEndReason>(replay = 1, extraBufferCapacity = 4)
    /** 服务端否定了这条会话（握手 401/403）。UI 收到即清会话回登录页。 */
    val sessionEnded: SharedFlow<SessionEndReason> = _sessionEnded.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var manualClose = false
    @Volatile private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var probeJob: Job? = null

    /** 上行 seq：**客户端本地单调自增**，只用于把响应配对回请求，不等于消息顺序号。 */
    private val seqGen = AtomicLong(0)
    fun nextSeq(): Long = seqGen.incrementAndGet()

    /** 连接成功后触发（上层据此发 sync_req）。 */
    var onConnected: (() -> Unit)? = null

    // ————————————————— 生命周期 —————————————————

    fun connect() {
        manualClose = false
        openSocket()
    }

    /** 退出登录 / 被踢：置 manualClose，之后任何唤醒信号都不再自动重连。 */
    fun disconnect() {
        manualClose = true
        cancelTimers()
        socket?.close(1000, "client closed")
        socket = null
        _state.value = ConnState.Idle
        log.i("ws_disconnected_by_client")
    }

    private fun openSocket() {
        if (manualClose) return
        // 已连接 / 正在握手都不再开新的。
        //
        // **漏掉 Connected 那一档会孤儿化上一条连接**：`socket` 字段被直接覆盖，
        // 旧的那条 WebSocket 没关、服务端仍认为它活着，同一台设备于是挂着两条连接
        // （消息会重复投递，且 sid 顶替语义被打乱）。
        // 真实触发路径：NetworkMonitor 在 App 启动瞬间就发 wake（此时本地已有 token，
        // 于是连上了），随后 restore() 探活成功，AppRoot 又调一次 connect() ——
        // 2026-09-07 回归实测抓到，日志里 26.349 与 34.261 各连了一次。
        if (_state.value != ConnState.Idle) {
            log.d("ws_open_skipped", "state" to _state.value.name)
            return
        }
        val token = tokenProvider()
        if (token.isNullOrEmpty()) {
            log.w("ws_no_token_skip_connect")
            return
        }
        _state.value = ConnState.Connecting
        val scheme = if (useTls) "wss" else "ws"
        val req = Request.Builder()
            .url("$scheme://$host/ws")
            // PROTOCOL §1 首选：凭据放请求头，不进 URI（URI 会被沿途日志记下）
            .header("Authorization", "Bearer $token")
            .build()
        log.i("ws_connecting", "host" to host, "attempt" to reconnectAttempts + 1)
        socket = ok.newWebSocket(req, Listener())
    }

    // ————————————————— 发送 —————————————————

    /**
     * 发一帧。未连接时返回 false（调用方决定是否入队重发）。
     *
     * `seq` **在确认能发之后才取**：默认参数会在函数体执行前求值，
     * 若在 socket 为 null 时也自增，断线期间的多次重试会烧掉一串序号，
     * 让服务端与客户端的请求-响应配对日志出现无法解释的空洞。
     */
    fun send(type: String, data: JsonElement? = null, seq: Long? = null): Boolean {
        val s = socket ?: return false
        val actualSeq = seq ?: nextSeq()
        val text = ProtocolJson.encodeToString(Envelope.serializer(), Envelope(type, actualSeq, data))
        val okSent = s.send(text)
        if (!okSent) log.w("ws_send_failed", "type" to type, "seq" to actualSeq)
        return okSent
    }

    // ————————————————— 唤醒 —————————————————

    /**
     * 网络恢复 / 回到前台时调用。判据见 [wakeActionFor]。
     */
    fun wake(reason: String) {
        when (wakeActionFor(_state.value, manualClose)) {
            WakeAction.None -> log.d("ws_wake_ignored", "reason" to reason, "state" to _state.value.name)
            WakeAction.Probe -> {
                log.i("ws_wake_probe", "reason" to reason)
                send(FrameType.PING)
                armProbeWatchdog()
            }
            WakeAction.Reconnect -> {
                log.i("ws_wake_reconnect", "reason" to reason, "attempts" to reconnectAttempts)
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempts = 0
                openSocket()
            }
        }
    }

    /**
     * 探活看门狗。**没有它，[WakeAction.Probe] 分支等于什么都没做。**
     *
     * 应用切后台后 socket 常是「看着还开着、对端早已断开」的僵尸态：
     * `send()` 把 PING 写进空气不报错，而本协议的 PING 从不校验 PONG，
     * 于是既没有写失败也永远等不到 onClosed——回到前台看着"已连接"，实则收不到任何消息。
     * 这是 Web 端 2026-08-30 由 `/code-review` 发现的真账，本端从第一版就带上。
     *
     * 故：探活发出即 arm，收到 PONG 即 clear；超时未收到就主动关掉这条僵尸连接，
     * 让既有的 onFailure/onClosed → 退避重连接手（此时 attempts 已归零，约 1s 重试）。
     */
    private fun armProbeWatchdog() {
        probeJob?.cancel()
        probeJob = scope.launch {
            delay(PROBE_TIMEOUT_MS)
            log.w("ws_probe_timeout_closing_zombie")
            socket?.cancel()   // cancel 而非 close：僵尸连接等不到正常关闭握手
            socket = null
            _state.value = ConnState.Idle
            scheduleReconnect()
        }
    }

    // ————————————————— 内部 —————————————————

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (!send(FrameType.PING)) break
            }
        }
    }

    private fun cancelTimers() {
        pingJob?.cancel(); pingJob = null
        probeJob?.cancel(); probeJob = null
        reconnectJob?.cancel(); reconnectJob = null
    }

    private fun scheduleReconnect() {
        if (manualClose || reconnectJob != null) return
        val delayMs = reconnectDelayMs(reconnectAttempts)
        reconnectAttempts++
        log.i("ws_reconnect_scheduled", "attempt" to reconnectAttempts, "delayMs" to delayMs)
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            openSocket()
        }
    }

    private fun endSession(reason: SessionEndReason) {
        // 停重连是关键：不停的话 10 分钟 token 缓存 + 稳定 device_id 会让
        // 「被踢下线」退化成一次静默自愈（iOS 2026-08-13 的真账）。
        manualClose = true
        cancelTimers()
        socket = null
        _state.value = ConnState.Idle
        log.w("ws_session_ended", "reason" to reason.name)
        scope.launch { _sessionEnded.emit(reason) }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 必须清掉在途的探活看门狗：它是上一条连接 arm 的，若不清，
            // 8s 后会把这条刚建好的健康连接 cancel 掉（/code-review 2026-09-07 查出）。
            probeJob?.cancel()
            probeJob = null
            reconnectAttempts = 0
            _state.value = ConnState.Connected
            log.i("ws_connected", "host" to host)
            startPing()
            onConnected?.invoke()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val env = try {
                ProtocolJson.decodeFromString(Envelope.serializer(), text)
            } catch (e: Exception) {
                // 解析不动也不能崩——PROTOCOL §2「收到未知 type 必须忽略、不崩」。
                log.w("ws_frame_parse_failed", "len" to text.length, "err" to e.javaClass.simpleName)
                return
            }
            if (env.type == FrameType.PONG) {
                probeJob?.cancel()
                probeJob = null
                return
            }
            // 用 emit 不用 tryEmit：缓冲满时挂起等消费，而不是默默丢一帧。
            // 丢一帧同步响应的代价是本地出现永久空洞，远大于短暂背压。
            scope.launch {
                try {
                    _frames.emit(env)
                } catch (e: Exception) {
                    log.w("ws_frame_emit_failed", "type" to env.type, "err" to e.javaClass.simpleName)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code ?: 0
            _state.value = ConnState.Idle
            socket = null
            // 与 onClosed 对称：连接没了，在途的 ping / 探活看门狗都不再有意义。
            // 漏掉这一句时，过期看门狗会在重连成功后掐死新连接。
            cancelTimers()
            when (handshakeFailureFor(status)) {
                HandshakeFailure.Revoked -> {
                    log.w("ws_handshake_401_revoked")
                    endSession(SessionEndReason.Revoked)
                }
                HandshakeFailure.Banned -> {
                    log.w("ws_handshake_403_banned")
                    endSession(SessionEndReason.Banned)
                }
                HandshakeFailure.Retryable -> {
                    log.w("ws_failed", "http" to status, "err" to t.javaClass.simpleName)
                    scheduleReconnect()
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = ConnState.Idle
            socket = null
            cancelTimers()
            log.i("ws_closed", "code" to code)
            scheduleReconnect()
        }
    }

    companion object {
        /** PROTOCOL §1：客户端每 25s 发 ping，服务端 60s 未收任何帧判离线。 */
        const val PING_INTERVAL_MS = 25_000L
        /** 探活等 PONG 的上限。与 Web 同值。 */
        const val PROBE_TIMEOUT_MS = 8_000L
    }
}
