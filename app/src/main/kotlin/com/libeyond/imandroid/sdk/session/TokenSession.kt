package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.sdk.api.AuthApi
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 恢复会话的结果。 */
sealed interface RestoreOutcome {
    /** 本地 token 仍然有效，直接用。 */
    data object Alive : RestoreOutcome
    /** 用凭据换到了新 token。 */
    data object Refreshed : RestoreOutcome
    /** 本地没有任何可用凭据——正常的"未登录"，不是错误。 */
    data object NoCredentials : RestoreOutcome
    /** 服务端明确判定这条会话已死（被踢 / 已注销 / 凭据过期）。**必须回登录页**。 */
    data class Dead(val code: Int) : RestoreOutcome
    /** 网络不通，说不准死活。**不要**据此清会话——清了就等于网络一抖就退出登录。 */
    data object Unreachable : RestoreOutcome
}

/**
 * 会话生命周期：探活 → 续期 → （才轮到）重新登录。
 *
 * 这三条路的顺序**即安全序**，搬自 Web 的 `sdk/tokenSession.ts`（2026-09-06 落地），
 * 那里有两条不能丢的判据，本端逐条照搬：
 *
 * ### 判据一：探活必须先于一切
 * 恢复会话时**先拿现有 token 打一次 `/devices`**，而不是直接重新登录。
 * 直接 `/login` 会**重新签发一条新会话**，把「这台设备已被踢下线」当场自愈掉
 * ——用户在另一台设备上点的"踢下线"就失效了。
 *
 * ### 判据二：续期被明确拒绝时，不回退到重新登录
 * 服务端回 `100101`/`100102` 意味着它认定**这条会话已死**。
 * 此时拿密码重登只会把「已被注销」洗成「又登上了」。必须回登录页，让用户显式重新登录。
 *
 * ### 判据三：分清「拒绝」与「连不上」
 * 传输层失败（[ApiException.isTransport]）说明**说不准**，一律按 [RestoreOutcome.Unreachable]
 * 处理、保留本地会话。把它当"会话已死"会让地铁里进一次隧道就被登出。
 */
class TokenSession(
    private val store: SessionStore,
    private val auth: AuthApi,
    /** 探活用：拿当前 token 打一个需要鉴权的轻量接口。 */
    private val probe: suspend () -> Unit,
) {
    private val log = IMLog.tag("IM.Session")

    /** 并发续期串行化：多个请求同时撞 100102 时只真发一次。 */
    private val refreshLock = Mutex()

    val token: String? get() = store.token
    val uid: String? get() = store.uid

    /**
     * 冷启动恢复会话。判定全部委托给 [RestoreDecision]（那张表才是唯一真相源），
     * 本方法只负责发请求与落地副作用。
     */
    suspend fun restore(): RestoreOutcome {
        // —— 判据一：先探活，不要直接重登 ——
        var probeCode: Int? = null
        var probeTransport = false
        if (store.isLoggedIn) {
            try {
                probe()
            } catch (e: ApiException) {
                probeCode = e.code
                probeTransport = e.isTransport
            }
        }

        when (RestoreDecision.afterProbe(store.isLoggedIn, probeCode, probeTransport)) {
            RestoreDecision.Step.NoCredentials -> {
                log.i("session_restore_no_credentials")
                return RestoreOutcome.NoCredentials
            }
            RestoreDecision.Step.Alive -> {
                log.i("session_restore_alive", "uid" to store.uid)
                IMLog.currentUid = store.uid ?: "-"
                return RestoreOutcome.Alive
            }
            RestoreDecision.Step.Unreachable -> {
                log.w("session_restore_unreachable", "code" to probeCode)
                return RestoreOutcome.Unreachable
            }
            else -> log.i("session_restore_token_dead", "code" to probeCode)
        }

        // —— token 死了，试凭据续期 ——
        val r = refreshNow()
        val hasCred = !store.refreshToken.isNullOrEmpty()
        val (code, transport) = when (r) {
            is RefreshOutcome.Ok -> null to false
            is RefreshOutcome.NoCredential -> ErrCode.TOKEN_INVALID to false
            is RefreshOutcome.Rejected -> r.code to false
            is RefreshOutcome.Unreachable -> ErrCode.TOKEN_INVALID to true
        }
        return when (RestoreDecision.afterRefresh(
            hasCredential = hasCred && r !is RefreshOutcome.NoCredential,
            refreshErrorCode = code,
            refreshWasTransport = transport,
        )) {
            RestoreDecision.Step.Alive -> RestoreOutcome.Refreshed
            RestoreDecision.Step.Unreachable -> RestoreOutcome.Unreachable
            // 判据二：明确拒绝 → 清会话回登录页，**不**回退重登
            else -> {
                store.clear()
                RestoreOutcome.Dead((r as? RefreshOutcome.Rejected)?.code ?: ErrCode.TOKEN_INVALID)
            }
        }
    }

    sealed interface RefreshOutcome {
        data object Ok : RefreshOutcome
        data object NoCredential : RefreshOutcome
        data class Rejected(val code: Int) : RefreshOutcome
        data object Unreachable : RefreshOutcome
    }

    /**
     * 续一次访问 token。并发调用只有一个真发请求，其余等它的结果。
     *
     * 服务端**不轮换**凭据，故成功后只换 `token`，`refreshToken` 原样保留。
     */
    suspend fun refreshNow(): RefreshOutcome = refreshLock.withLock {
        val cred = store.refreshToken
        if (cred.isNullOrEmpty()) {
            log.i("session_refresh_no_credential")
            return RefreshOutcome.NoCredential
        }
        try {
            val r = auth.refresh(cred)
            store.save(r.token, r.uid, r.refreshToken, null)
            IMLog.currentUid = r.uid
            log.i("session_refreshed", "uid" to r.uid)
            RefreshOutcome.Ok
        } catch (e: ApiException) {
            if (e.isTransport) {
                log.w("session_refresh_unreachable")
                RefreshOutcome.Unreachable
            } else {
                log.w("session_refresh_rejected", "code" to e.code)
                RefreshOutcome.Rejected(e.code)
            }
        }
    }

    /** 登录成功后写入会话。 */
    fun adopt(token: String, uid: String, refreshToken: String?, username: String?) {
        store.save(token, uid, refreshToken, username)
        IMLog.currentUid = uid
    }

    /**
     * 退出登录：**先调服务端吊销，再清本地**。
     * 反过来（先清本地）会让 `logout` 拿不到 token，服务端会话就留在那里了。
     * 服务端调用失败也照样清本地——用户点了退出就得退出。
     */
    suspend fun logout() {
        try {
            auth.logout()
        } catch (e: ApiException) {
            log.w("logout_server_call_failed", "code" to e.code)
        }
        store.clear()
        IMLog.currentUid = "-"
    }
}
