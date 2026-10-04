package com.libeyond.imandroid.rtc

/**
 * 用户在来电横幅（离线推送，`fcm/CallNotifications.kt`）上点了「接听」/「拒绝」，但 App 这时多半还没连上
 * 通话服务——点击把 App 拉起来，连上之后服务端才开始振铃、SDK 才抛 `onCallReceived`（PUSH_M5_DESIGN §3.8）。
 * 这里先记下「对哪通电话做什么」，等那通来电真的到了再执行（[RtcCall]）。
 *
 * 只记一条（同一时刻只会有一通来电）；过了 [TTL_MS] 当作作废——振铃最长 120 秒，再晚到的同 id 来电不可能是这一通。
 * 纯 Kotlin、时间由调用方传入，JVM 单测不需要 Android。
 */
object PendingCallAction {
    const val TTL_MS = 120_000L

    private data class Entry(val callId: String, val accept: Boolean, val atMs: Long)

    @Volatile private var entry: Entry? = null

    fun request(callId: String, accept: Boolean, nowMs: Long) {
        if (callId.isBlank()) return
        entry = Entry(callId, accept, nowMs)
    }

    /** 这通来电有没有待执行的动作：有就取走（只执行一次），返回 true = 接听、false = 拒绝；没有返回 null。 */
    @Synchronized
    fun consume(callId: String, nowMs: Long): Boolean? {
        val e = entry ?: return null
        if (nowMs - e.atMs > TTL_MS) {
            entry = null
            return null
        }
        if (e.callId != callId) return null
        entry = null
        return e.accept
    }
}
