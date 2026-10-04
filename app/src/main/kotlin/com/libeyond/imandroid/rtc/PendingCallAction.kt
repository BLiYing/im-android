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

/**
 * 横幅上的「接听」能不能由宿主直接替用户接（[RtcCall] 调 `engine.accept`）。
 *
 * Kit 自己的接听入口（`IMCallKit.answer`：先过权限门、按来电页的摄像头开关同步采集、开本地预览）是 `internal` 的，
 * 宿主调不到；直接 `engine.accept` 会绕过它。所以只在**绕过也不出事**的时候直接接：
 * - 麦克风已授权——否则接通了对方听不到人，也没人弹授权框；
 * - 不是 1v1 视频——1v1 视频的摄像头默认开、要本地预览，那一套只有 Kit 会做。群视频 Kit 在来电时就关了摄像头。
 * 其余情况不接：点按钮已经把 App 拉到前台，Kit 的来电界面就在眼前，用户再点一次「接听」，由 Kit 走完整流程。
 * TODO：im-rtc Android SDK 公开 `IMCallKit.answer()`（iOS 的 `IMCallController.accept` 已公开）后改调它，去掉这层判定。
 */
internal fun autoAcceptAllowed(micGranted: Boolean, mediaType: String, isGroup: Boolean): Boolean =
    micGranted && (mediaType != "video" || isGroup)
