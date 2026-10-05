package com.libeyond.imandroid.rtc

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMCallSummary
import com.imrtc.engine.IMKickedOutReason
import com.libeyond.imandroid.data.AppActive
import com.libeyond.imandroid.fcm.CallNotifications
import com.libeyond.imandroid.sdk.api.RtcApi
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.launch

/**
 * [RtcCall] 的引擎事件监听（从 RtcCall.kt 拆出，守 600 行红线）。状态全在 [RtcCall]，这里只翻译事件；
 * 每个回调先看 [stale]：旧一代引擎迟到的回调一律不算数。
 */
internal class RtcHostListener(
    private val gen: Long,
    private val rtcApi: RtcApi,
    private val config: RtcConfig,
) : IMCallEngineListener {
    private val log = IMLog.tag("IM.Rtc")
    private val stale: Boolean get() = gen != RtcCall.generation

    override fun onConnected(sessionId: String, resumed: Boolean) {
        if (!stale) log.i("rtc_connected", "session" to sessionId, "resumed" to resumed)
    }

    /** 来电：只记下这通是不是群通话、哪个群，好让解析器读对的成员表（不发任何请求）。 */
    override fun onCallReceived(
        callId: String, caller: String, inviter: String, calleeIds: List<String>, joinedIds: List<String>,
        mediaType: String, isGroup: Boolean, chatGroupId: String, userData: String,
    ) {
        if (stale) return
        RtcCall.profileResolver?.groupId = if (isGroup) chatGroupId else ""
        RtcCall._inCall.value = true
        RtcCall.ringingCallId = callId
        RtcCall.ringingMedia = mediaType
        RtcCall.ringingIsGroup = isGroup
        // App 在前台：SDK 的来电界面接手了，通知栏里那条离线推送的来电横幅（如果有）就多余了。
        // 在后台：系统不让弹来电界面，横幅是唯一入口，留着、只静音（Kit 已经在响）。
        RtcCall.appContext?.let { if (AppActive.current) CallNotifications.cancel(it, callId) else CallNotifications.silence(it, callId) }
        // 用户是点着横幅上的按钮把 App 拉起来的：来电一到就替他接 / 拒。晚一拍执行，让 Kit 先把来电界面立起来。
        PendingCallAction.consume(callId, System.currentTimeMillis())?.let { accept -> RtcCall.main.post { RtcCall.act(accept) } }
    }

    /** 接通（主被叫都抛）：响铃阶段 [onCallReceived] 已经置过一次，这里覆盖同一个值，兜住主叫自己发起、没经过 onCallReceived 的路径。 */
    override fun onCallBegin(
        callId: String, roomId: String, mediaType: String, isGroup: Boolean,
        role: String, caller: String, chatGroupId: String, userData: String,
    ) {
        if (stale) return
        RtcCall._inCall.value = true
        RtcCall.ringingCallId = null
    }

    /** 每通电话都会到达（不分角色）：只转发信号给 [RtcCall.onCallEnded]，不带数据——那是通话记录专属的落库判定。 */
    override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
        if (stale) return
        RtcCall._inCall.value = false
        RtcCall.ringingCallId = null
        RtcCall.appContext?.let { CallNotifications.cancel(it, callId) }
        RtcCall.onCallEnded?.invoke()
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
        if (plan != null) RtcCall.onCallRecord?.invoke(plan)
    }

    override fun onDisconnected(code: Int, willReconnect: Boolean) {
        if (!stale) log.w("rtc_disconnected", "code" to code, "reconnect" to willReconnect)
    }

    override fun onKickedOut(reason: IMKickedOutReason) {
        if (stale) return
        log.w("rtc_kicked_out", "reason" to reason.name)
        val ctx = RtcCall.appContext ?: return
        when (reason) {
            // 票不好使：本机再换一张重来，用户无感。**必须先 teardown()**：start() 顶部「同账号同
            // 设备重复调用是空操作」的幂等判断只看 engine 是否非空，这里 engine 还在（没人调过
            // stop），不先清掉的话下面这次 start() 会被当成空操作直接跳过，换票重登永远不会发生。
            IMKickedOutReason.AUTH_EXPIRED -> RtcCall.main.post {
                if (stale) return@post // 排队期间登出 / 切号了：别把引擎又拉起来
                RtcCall.teardown()
                RtcCall.start(
                    ctx, RtcCall.uid, RtcCall.deviceId, rtcApi,
                    RtcCall.profileResolver, RtcCall.inviteProvider, config,
                )
            }
            // 别处登录 / 被吊销 / 参数被拒：换票救不了，也不自动重连，停下来等人看日志。
            IMKickedOutReason.TAKEN_OVER, IMKickedOutReason.CONFIG_REJECTED -> RtcCall.main.post { RtcCall.stop() }
        }
    }

    override fun onTokenWillExpire(expiresAtMs: Long) {
        if (stale) return
        // 下一次重连生效，不打断当前通话。换票是异步的，回来时可能已经 stop 过（同上方
        // start 的 generation 判定）。
        RtcCall.scope.launch {
            val token = RtcCall.signToken(rtcApi) ?: return@launch
            if (stale) return@launch
            RtcCall.engine?.updateToken(token, 0L)
            log.i("rtc_token_renewed")
        }
    }

    override fun onError(code: Int, name: String, message: String, forType: String) {
        if (!stale) log.w("rtc_error", "code" to code, "name" to name, "for" to forType, "msg" to message)
    }
}
