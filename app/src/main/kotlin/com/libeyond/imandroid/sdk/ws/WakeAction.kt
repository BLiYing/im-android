package com.libeyond.imandroid.sdk.ws

import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.session.TokenSession

/** 连接状态。 */
enum class ConnState { Idle, Connecting, Connected }

/** 唤醒信号该做什么。 */
enum class WakeAction { None, Reconnect, Probe }

/**
 * 网络恢复 / 回前台时的唤醒判据——与 iOS `IMSocketWakeActionFor`、
 * Web `sdk/wake.ts#wakeActionFor` **同一口径**（`docs/CLIENT_PARITY.md`「网络恢复秒连」行）。
 *
 * 真正的坑不在"连一下"，而在**什么时候不该连**：
 * - `manualClose` 后不能连：退出登录 / 被踢下线都置它，自动重连等于把用户的登出撤销，
 *   也会让「被踢」退化成一次临时抖动；
 * - `Connecting` 时不能再连：会掐掉正在握手的那条，反而更慢；
 * - `Connected` 时只**探活**：重连等于白白断一次好连接。
 */
fun wakeActionFor(state: ConnState, manualClose: Boolean): WakeAction = when {
    manualClose -> WakeAction.None
    state == ConnState.Connected -> WakeAction.Probe
    state == ConnState.Connecting -> WakeAction.None
    else -> WakeAction.Reconnect
}

/**
 * 握手失败该怎么处理——**这条不能只看"连不上"**。
 *
 * iOS 吃过一次亏（2026-08-13）：把握手 401 当普通网络错重连，配合 10 分钟 token 缓存
 * 与稳定 device_id，「被踢下线」退化成 ≤10min 的静默自愈——用户在另一台设备点的"踢下线"
 * 看起来生效了，其实对方过一会儿又连上来了。
 *
 * - **401**：服务端不认这枚 token——**可能只是过期了**（JWT 24h），也可能 sid 已被吊销
 *   （gateway 两种都回 401，只是正文不同）。先续期再说，见 [unauthorizedActionFor]。
 * - **403**：账号被封（PROTOCOL §7）。停重连回登录页，给封号文案。
 * - 其余：普通网络问题，照常退避重连。
 */
enum class HandshakeFailure { Unauthorized, Banned, Retryable }

fun handshakeFailureFor(httpStatus: Int): HandshakeFailure = when (httpStatus) {
    401 -> HandshakeFailure.Unauthorized
    403 -> HandshakeFailure.Banned
    else -> HandshakeFailure.Retryable
}

/**
 * 握手 401 之后续期的结果（由 [tokenRefreshFor] 从 `TokenSession.refreshNow` 映射过来）。
 * Unreachable 也包括服务端临时出错（5xx / 限流）：说不准死活，退避重试，不登出。
 */
enum class TokenRefresh { Refreshed, Unreachable, Rejected, Expired, Banned }

/** 握手 401 之后该做什么。 */
enum class UnauthorizedAction { Refresh, EndRevoked }

/**
 * 握手 401 → 先续期，**只有续期被服务端明确拒绝才算被踢**（与 `RestoreDecision` 判据二同一口径）。
 *
 * 2026-10-08 Pixel 实测：登录满 24h 后冷启动，网络唤醒抢在 restore 之前拿过期 token 连上来，
 * 握手 401（正文 `unauthorized`）被当成「已被吊销」，本机清了凭据、提示「已在别处登录」——
 * 服务端会话和 FCM 令牌却都还在，来电推送照样弹。
 *
 * 续期拒绝吊销的 sid（device `RefreshSession` 查 revoked_at），所以真被踢的那条照样回登录页：
 * - 没有续期能力（测试 / 未接线）→ 直接按吊销处理（旧行为）；
 * - 撞 401 的正是**续期之后开的那条连接**（`failedGen == refreshRetryGen`）→ 不再续，按吊销处理（防死循环）。
 *   别的连接（之后的重连 / 重新登录）撞 401 照样可以再续。
 */
fun unauthorizedActionFor(canRefresh: Boolean, failedGen: Long, refreshRetryGen: Long): UnauthorizedAction =
    if (canRefresh && (refreshRetryGen == 0L || failedGen != refreshRetryGen)) UnauthorizedAction.Refresh
    else UnauthorizedAction.EndRevoked

/**
 * 指数退避：`base * 2^attempt`，封顶 `max`。与 Web 同参（1s 起、30s 封顶）。
 * `attempt` 从 0 起算；连接成功后调用方须把它清零。
 */
fun reconnectDelayMs(attempt: Int, base: Long = 1_000, max: Long = 30_000): Long {
    if (attempt <= 0) return base
    // 左移超过 62 位会溢出；先夹住指数再算。
    val shift = attempt.coerceAtMost(20)
    val d = base shl shift
    return if (d <= 0 || d > max) max else d
}

/**
 * `TokenSession.refreshNow` 的结果 → 握手 401 之后怎么收场。
 *
 * **只有鉴权类拒绝才结束会话**（/code-review 2026-10-08）：此前一切非传输层错误都当「被踢」，
 * 封号用户看到的是「已在别处登录」，续期接口偶发 5xx / 限流也会把人登出。
 * - 封号（200003）→ Banned，给封号文案；
 * - 凭据到寿（100102）或本机压根没有续期凭据 → Expired，「登录已过期」；
 * - 凭据无效 / sid 已吊销（100101）→ Rejected，「已在别处登录或设备已被移除」；
 * - 其余业务码（服务端内部错误等）→ 说不准，当 Unreachable 退避重试。
 */
fun tokenRefreshFor(outcome: TokenSession.RefreshOutcome): TokenRefresh = when (outcome) {
    TokenSession.RefreshOutcome.Ok -> TokenRefresh.Refreshed
    TokenSession.RefreshOutcome.Unreachable -> TokenRefresh.Unreachable
    TokenSession.RefreshOutcome.NoCredential -> TokenRefresh.Expired
    is TokenSession.RefreshOutcome.Rejected -> when (outcome.code) {
        ErrCode.ACCOUNT_BANNED -> TokenRefresh.Banned
        ErrCode.TOKEN_EXPIRED -> TokenRefresh.Expired
        ErrCode.TOKEN_INVALID -> TokenRefresh.Rejected
        else -> TokenRefresh.Unreachable
    }
}
