package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [FcmTokenStore.reportToken] 该怎么处理——纯函数，单测钉住。 */
sealed interface FcmTokenAction {
    /** 没有登录会话：记成"待补报"，不发请求（没有 Bearer token，发了也是白 401 一次）。 */
    data object Defer : FcmTokenAction

    /** 与已经上报过的那一枚相同：不重复发（服务端 upsert 幂等，重复发不是错，只是没必要）。 */
    data object Skip : FcmTokenAction

    /** 有会话、且是没报过的新值：真的发 PUT。 */
    data object Put : FcmTokenAction
}

object FcmTokenSync {
    fun decide(hasSession: Boolean, token: String, alreadyReported: String?): FcmTokenAction = when {
        !hasSession -> FcmTokenAction.Defer
        token == alreadyReported -> FcmTokenAction.Skip
        else -> FcmTokenAction.Put
    }
}

/**
 * FCM 令牌上报的编排（M5 批次 2，`../../IMServer/docs/design/PUSH_M5_DESIGN.md` §1.1）。
 *
 * **时序坑**（iOS `IMProgram` 已经踩过一次，这里照抄教训，不重演）：
 * `FirebaseMessagingService.onNewToken` 可能在用户登录之前就回调（App 冷启动、还没恢复会话/还没
 * 登录时 FCM 服务已经在跑）——这时没有 Bearer token，PUT 会以 401 收场。本类**不在没有会话时
 * 发请求**：[reportToken] 在没登录时把 token 记成"待上报"（[FcmTokenSync.decide] 的 `Defer` 分支），
 * 交给 [onSessionReady] 在登录成功 / 会话恢复、WS 真正连上时补报一次（接线在 `IMClient` 的
 * `socket.state` 订阅里，同 `AccountNotifySettingsStore`/`DownloadSettingsStore` 的"连上即补一次"
 * 同一手法）。
 *
 * 接口以函数注入（`hasSession`/`isEnabled`/`persistEnabled`/`put`/`delete`），JVM 单测不需要真的
 * HTTP 客户端、SharedPreferences 或 FirebaseMessaging。
 */
class FcmTokenStore(
    /** 当前是否有可用的登录会话（能拿到 Bearer token）。 */
    private val hasSession: () -> Boolean,
    /** 用户是否开着「接收离线推送」（本设备本地偏好，见 [FcmPreference]）。关着时任何上报都不发。 */
    private val isEnabled: () -> Boolean,
    private val persistEnabled: (Boolean) -> Unit,
    private val put: suspend (token: String) -> Unit,
    private val delete: suspend () -> Unit,
) {
    private val log = IMLog.tag("IM.Fcm")

    private val _reported = MutableStateFlow<String?>(null)
    /** 最近一次成功 PUT 上去的 token；测试/诊断可读。 */
    val reportedToken: StateFlow<String?> = _reported.asStateFlow()

    private val _pending = MutableStateFlow<String?>(null)
    /** 有 token 但还没能发出去（没会话 / 发送失败），下次 [onSessionReady] 补报。 */
    val pendingToken: StateFlow<String?> = _pending.asStateFlow()

    /** [com.libeyond.imandroid.fcm.FcmMessagingService.onNewToken] 与"连上即补一次"都调这个。 */
    suspend fun reportToken(token: String) {
        if (token.isBlank() || !isEnabled()) return
        when (FcmTokenSync.decide(hasSession(), token, _reported.value)) {
            FcmTokenAction.Skip -> Unit
            FcmTokenAction.Defer -> {
                _pending.value = token
                log.i("fcm_token_deferred_no_session")
            }
            FcmTokenAction.Put -> send(token)
        }
    }

    /** 登录成功 / 冷启动恢复会话成功、WS 连上时调用：把之前攒下的 token（若有）补报一次。 */
    suspend fun onSessionReady() {
        _pending.value?.let { reportToken(it) }
    }

    private suspend fun send(token: String) {
        attempt { put(token) }.fold(
            onSuccess = { _reported.value = token; _pending.value = null },
            onFailure = {
                _pending.value = token
                log.w("fcm_token_report_failed", "err" to it.javaClass.simpleName)
            },
        )
    }

    /**
     * 设置页「接收离线推送」开关（`ui/NotificationSettingsHost.kt`）。
     * 开：记住偏好，`fetchCurrentToken` 取一次当前 token 再报（关闭期间收到的 `onNewToken` 会被
     * [isEnabled] 挡掉，手头未必有能报的新值，主动取一次最稳）；
     * 关：记住偏好，删服务端令牌，本地也忘掉"已上报"状态（下次重开要重新走一遍 PUT）。
     */
    suspend fun setEnabled(v: Boolean, fetchCurrentToken: suspend () -> String?) {
        persistEnabled(v)
        if (v) {
            fetchCurrentToken()?.let { reportToken(it) }
            return
        }
        attempt { delete() }.fold(
            onSuccess = { log.i("fcm_token_deleted") },
            onFailure = { log.w("fcm_token_delete_failed", "err" to it.javaClass.simpleName) },
        )
        _reported.value = null
        _pending.value = null
    }

    /**
     * 退出登录 / 被踢：忘掉"已上报"状态。**不调服务端 delete**——正常退出登录时服务端按会话联删
     * 令牌，客户端不需要每次都补一刀（父任务简报明确约定）；这里只是本地状态复位，避免下一个账号
     * 在同一台设备登录时，因为 token 字符串碰巧没变就被 [FcmTokenSync.decide] 误判成"已经报过了"
     * 而跳过新账号的首次上报。
     */
    fun forget() {
        _reported.value = null
        _pending.value = null
    }

    private suspend inline fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
