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

    private val _frames = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)
    /** 所有下行帧。上层按 `type` 分派。 */
    val frames: SharedFlow<Envelope> = _frames.asSharedFlow()

    private val _sessionEnded = MutableSharedFlow<SessionEndReason>(extraBufferCapacity = 4)
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
        if (_state.value == ConnState.Connecting) return
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

    /** 发一帧。未连接时返回 false（调用方决定是否入队重发）。 */
    fun send(type: String, data: JsonElement? = null, seq: Long = nextSeq()): Boolean {
        val s = socket ?: return false
        val text = ProtocolJson.encodeToString(Envelope.serializer(), Envelope(type, seq, data))
        val okSent = s.send(text)
        if (!okSent) log.w("ws_send_failed", "type" to type, "seq" to seq)
        return okSent
    }

    // ————————————————— 唤醒 —————————————————

    /**
     * 网络恢复 / 回到前台时调用。判据见 [wakeActionFor]。
     */
    fun wake(reason: String) {
        when (val action = wakeActionFor(_state.value, manualClose)) {
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
            else -> log.d("ws_wake_noop", "action" to action.name)
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
            if (!_frames.tryEmit(env)) {
                log.w("ws_frame_dropped_buffer_full", "type" to env.type)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code ?: 0
            _state.value = ConnState.Idle
            socket = null
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
