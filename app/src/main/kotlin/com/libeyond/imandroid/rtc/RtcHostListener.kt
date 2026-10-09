package com.libeyond.imandroid.rtc

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMCallSummary
import com.imrtc.engine.IMKickedOutReason
import com.libeyond.imandroid.sdk.logging.IMLog

/** [RtcCall] 暴露给事件监听的窄接口：监听只通过它改宿主状态，`generation` / `_inCall` 等保持私有。 */
internal interface RtcHostBridge {
    fun isStale(gen: Long): Boolean
    fun onIncoming(callId: String, mediaType: String, isGroup: Boolean, chatGroupId: String)
    fun onBegin()
    fun onEnd(callId: String)
    fun onRecord(plan: CallRecordPlan)
    /** [gen] 是收到事件时的引擎代数：主线程上真正收引擎前要再核一次，别误收掉期间重建出来的新引擎。 */
    fun onFatalKickedOut(gen: Long)
}

/**
 * [RtcCall] 的引擎事件监听（从 RtcCall.kt 拆出，守 600 行红线）。只翻译事件；
 * 每个回调先看 [stale]：旧一代引擎迟到的回调一律不算数。
 */
internal class RtcHostListener(
    private val host: RtcHostBridge,
    private val gen: Long,
) : IMCallEngineListener {
    private val log = IMLog.tag("IM.Rtc")
    private val stale: Boolean get() = host.isStale(gen)

    override fun onConnected(sessionId: String, resumed: Boolean) {
        if (!stale) log.i("rtc_connected", "session" to sessionId, "resumed" to resumed)
    }

    /** 来电：只记下这通是不是群通话、哪个群，好让解析器读对的成员表（不发任何请求）。 */
    override fun onCallReceived(
        callId: String, caller: String, inviter: String, calleeIds: List<String>, joinedIds: List<String>,
        mediaType: String, isGroup: Boolean, chatGroupId: String, userData: String,
    ) {
        if (!stale) host.onIncoming(callId, mediaType, isGroup, chatGroupId)
    }

    /** 接通（主被叫都抛）：兜住主叫自己发起、没经过 onCallReceived 的路径。 */
    override fun onCallBegin(
        callId: String, roomId: String, mediaType: String, isGroup: Boolean,
        role: String, caller: String, chatGroupId: String, userData: String,
    ) {
        if (!stale) host.onBegin()
    }

    /** 每通电话都会到达（不分角色）：只转发信号给 [RtcCall.onCallEnded]，不带数据——那是通话记录专属的落库判定。 */
    override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
        if (!stale) host.onEnd(callId)
    }

    /** 每通电话恰好一次、晚于 onCallEnd。宿主只在 role==caller 时发记录，别的都不用管。 */
    override fun onCallSummary(summary: IMCallSummary) {
        if (stale) return
        val plan = RtcCallRecords.planFor(summary)
        log.i(
            "rtc_call_summary",
            "cid" to summary.callId, "role" to summary.role, "reason" to summary.reason.wire,
            "d" to summary.durationSec, "record" to (plan != null),
        )
        if (plan != null) host.onRecord(plan)
    }

    override fun onDisconnected(code: Int, willReconnect: Boolean) {
        if (!stale) log.w("rtc_disconnected", "code" to code, "reconnect" to willReconnect)
    }

    override fun onKickedOut(reason: IMKickedOutReason) {
        if (stale) return
        log.w("rtc_kicked_out", "reason" to reason.name)
        when (reason) {
            // 票失效：Kit 自己取新票重登（tokenProvider，im-rtc 2.2.0），宿主不用管。
            IMKickedOutReason.AUTH_EXPIRED -> Unit
            IMKickedOutReason.TAKEN_OVER, IMKickedOutReason.CONFIG_REJECTED -> host.onFatalKickedOut(gen)
        }
    }

    override fun onError(code: Int, name: String, message: String, forType: String) {
        if (!stale) log.w("rtc_error", "code" to code, "name" to name, "for" to forType, "msg" to message)
    }
}
