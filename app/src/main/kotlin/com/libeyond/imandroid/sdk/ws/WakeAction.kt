package com.libeyond.imandroid.sdk.ws

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
 * - **401**：本机 sid 已被吊销（被踢 / 已注销）。停重连 + 清会话 + 回登录页。
 * - **403**：账号被封（PROTOCOL §7）。同样停重连回登录页，但语义不同，要给不同文案。
 * - 其余：普通网络问题，照常退避重连。
 */
enum class HandshakeFailure { Revoked, Banned, Retryable }

fun handshakeFailureFor(httpStatus: Int): HandshakeFailure = when (httpStatus) {
    401 -> HandshakeFailure.Revoked
    403 -> HandshakeFailure.Banned
    else -> HandshakeFailure.Retryable
}

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
