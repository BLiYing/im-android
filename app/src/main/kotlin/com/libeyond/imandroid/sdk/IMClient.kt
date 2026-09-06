package com.libeyond.imandroid.sdk

import android.content.Context
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.sdk.api.AuthApi
import com.libeyond.imandroid.sdk.api.DevicesApi
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.session.DeviceIdentity
import com.libeyond.imandroid.sdk.session.RestoreOutcome
import com.libeyond.imandroid.sdk.session.SessionStore
import com.libeyond.imandroid.sdk.session.TokenSession
import com.libeyond.imandroid.sdk.ws.IMSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow

/**
 * SDK 门面——把 HTTP / 会话 / WebSocket 接在一起，供 UI 使用。
 * 对应 Web 的 `IMClient`、iOS 的 `IMSocketManager` + `IMHTTPService` 组合。
 *
 * **UI 只跟这个类打交道**，不直接摸 OkHttp / SharedPreferences / 协议帧。
 */
class IMClient(context: Context) {

    private val log = IMLog.tag("IM.Client")
    val scope = CoroutineScope(SupervisorJob())

    /** 服务器地址。debug 默认 `10.0.2.2:8080`（模拟器指向宿主机）。 */
    var host: String = BuildConfig.DEFAULT_HOST
        set(v) { field = v; http.host = v; socket.host = v }

    private val session = SessionStore(context)
    private val device = DeviceIdentity(context)

    private val http = HttpClient(
        host = host,
        useTls = BuildConfig.USE_TLS,
        tokenProvider = { session.token },
    )

    private val auth = AuthApi(http, device)
    private val devices = DevicesApi(http)

    val tokens = TokenSession(session, auth, probe = { devices.probe() })

    val socket = IMSocketManager(
        scope = scope,
        host = host,
        useTls = BuildConfig.USE_TLS,
        tokenProvider = { session.token },
    )

    /** 服务端否定了这条会话（被踢 / 被封）。UI 订阅它回登录页。 */
    val sessionEnded: SharedFlow<com.libeyond.imandroid.sdk.ws.SessionEndReason> get() = socket.sessionEnded

    val uid: String? get() = session.uid
    val username: String? get() = session.username
    val isLoggedIn: Boolean get() = session.isLoggedIn

    init {
        // 会话被服务端否定 → 清本地凭据。**不在这里跳 UI**（那是 UI 的事），
        // 但必须清凭据，否则下次冷启动又拿着一枚已死的 token 去探活。
        scope.launch {
            socket.sessionEnded.collect {
                log.w("session_ended_clearing_credentials", "reason" to it.name)
                session.clear()
                IMLog.currentUid = "-"
            }
        }
    }

    /** 冷启动恢复会话。 */
    suspend fun restore(): RestoreOutcome = tokens.restore()

    /** 账号密码登录。成功后立即连 WS。 */
    suspend fun login(username: String, password: String?) {
        val r = auth.login(username, password)
        tokens.adopt(r.token, r.uid, r.refreshToken, username)
        log.i("login_ok", "uid" to r.uid)
        socket.connect()
    }

    /** 注册后自动登录（与 iOS/Web 一致：注册页不让用户再输一遍）。 */
    suspend fun register(username: String, password: String, nickname: String) {
        auth.register(username, password, nickname)
        login(username, password)
    }

    /** 已有有效会话时连接。 */
    fun connect() = socket.connect()

    suspend fun logout() {
        socket.disconnect()
        tokens.logout()
    }

    /** 网络恢复 / 回到前台。 */
    fun wake(reason: String) = socket.wake(reason)
}
