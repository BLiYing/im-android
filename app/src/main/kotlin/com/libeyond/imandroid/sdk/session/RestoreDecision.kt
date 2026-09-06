package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 会话恢复的**判定表**——从 [TokenSession] 里抽出来的纯函数，唯一真相源。
 *
 * 抽出来不是为了好看：判定本身是三条安全约束，必须能被直接测到。
 * 把它留在 `TokenSession.restore()` 里，测试就只能要么起真网络、要么在测试里
 * 复刻一份判定——**后者会漂移**（改了实现忘了改复刻件，测试照样全绿）。
 *
 * 三条判据的由来见 [TokenSession] 的类注释。
 */
object RestoreDecision {

    /** 一次调用的结果分类，与网络无关。 */
    enum class Step { Alive, TryRefresh, Dead, Unreachable, NoCredentials }

    /**
     * 探活结果 → 下一步。
     *
     * @param loggedIn 本地是否有 token+uid
     * @param probeErrorCode 探活失败时的业务码；成功传 null
     * @param probeWasTransport 探活失败是否为传输层失败（连不上，而非服务端拒绝）
     */
    fun afterProbe(
        loggedIn: Boolean,
        probeErrorCode: Int?,
        probeWasTransport: Boolean,
    ): Step = when {
        !loggedIn -> Step.NoCredentials
        probeErrorCode == null -> Step.Alive
        // 判据三：连不上 ≠ 会话已死。清了就等于网络一抖就登出。
        probeWasTransport -> Step.Unreachable
        // 非鉴权类错误（如服务端 500）没有否定会话本身，保留。
        !ErrCode.isAuthExpired(probeErrorCode) -> Step.Unreachable
        // token 确实死了，才轮到续期。
        else -> Step.TryRefresh
    }

    /**
     * 续期结果 → 下一步。
     *
     * @param hasCredential 本地是否有 refresh_token
     * @param refreshErrorCode 续期失败时的业务码；成功传 null
     * @param refreshWasTransport 续期失败是否为传输层失败
     */
    fun afterRefresh(
        hasCredential: Boolean,
        refreshErrorCode: Int?,
        refreshWasTransport: Boolean,
    ): Step = when {
        !hasCredential -> Step.Dead
        refreshErrorCode == null -> Step.Alive
        refreshWasTransport -> Step.Unreachable
        // 判据二：服务端明确拒绝 = 认定这条会话已死。
        // **不得**回退到重新登录——那只会把「已被注销」洗成「又登上了」。
        else -> Step.Dead
    }
}
