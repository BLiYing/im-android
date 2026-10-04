package com.libeyond.imandroid.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMCallHistoryPage
import com.imrtc.engine.IMCallOptions
import com.imrtc.engine.IMCallSummary
import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.IMRTCError
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMVideoProfile
import com.imrtc.engine.webrtc.IMWebRTCAdapter
import com.imrtc.uikit.IMCallKit
import com.imrtc.uikit.IMCallKitConfig
import com.imrtc.uikit.IMLocale
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.data.ResolvedLanguage
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.RtcApi
import com.libeyond.imandroid.sdk.api.RtcTokenResult
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.data.AppActive
import com.libeyond.imandroid.fcm.CallNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * im-rtc 通话的宿主侧接入点（**只有主线程调用**）。
 *
 * - [start]：用户进入主界面（IM 已登录）时调用——建引擎、向 IMServer 换票、登录 im-rtc，此后能拨也能接。
 * - [stop]：退出 / 被踢 / 被封离开主界面时调用——销毁引擎、断开 im-rtc。**不停的话换账号会有两条连接，服务端踢掉其中一条。**
 * - [placeSingle] / [placeGroup]：业务入口调用，界面全部由 Kit 接管。
 *
 * 票从哪来只在 [signToken] 一处（调 IMServer `POST /api/v1/rtc/token` 代为向 im-rtc-server
 * 换票，本端不知道任何签名密钥）。对端：iOS `IMRtcCall.m`、im-web `src/rtc/rtcEngine.ts`。
 */
object RtcCall {

    private val log = IMLog.tag("IM.Rtc")
    private val main = Handler(Looper.getMainLooper())

    /** 只用于换票这类"回调触发、需要挂起"的场景（续票 / 被踢后重签）；本身长期存活，跟随进程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var appContext: Context? = null
    private var engine: IMCallEngine? = null
    private var uid: String = ""
    private var deviceId: String = ""
    private var profileResolver: RtcProfileResolver? = null
    private var inviteProvider: com.imrtc.uikit.IMInviteMemberProvider? = null

    /** 每次 start/stop 加一：旧引擎迟到的回调（stale）一律不算数，别改动新一代的状态。 */
    private var generation = 0L

    /** 上一次 [start] 是否因换票失败而没能登录（引擎已回收）；[unavailableReason] 据此给出更准的提示。 */
    @Volatile private var tokenFetchFailed = false

    /**
     * 通话结束后该落一条通话记录时的回调（只对**主叫**触发，见 [RtcCallRecords]）。
     * 由拿得到 IM 客户端的地方（AppRoot）接线；没接线 = 不发，只写日志。主线程。
     */
    @Volatile var onCallRecord: ((CallRecordPlan) -> Unit)? = null

    /**
     * 每通电话结束时触发（双方都会收到，不分角色）——设置 ▸ 最近通话页面留在页面上时靠它重拉首页
     * （CALL_HISTORY_DESIGN.md §3）。没接线的页面 = 不触发，只是一个信号，不带数据，
     * 页面自己重新调 [fetchCallHistory] 取权威结果。主线程。
     */
    @Volatile var onCallEnded: (() -> Unit)? = null

    /** 正在响、还没接通 / 结束的那通来电（[applyNotificationAction] 用：点按钮时它可能已经在响了）。 */
    @Volatile private var ringingCallId: String? = null

    /**
     * 来电横幅上点了「接听」/「拒绝」（MainActivity 收到 intent 时调）。这通已经在响就当场照做；
     * 还没到（App 刚被拉起、还没连上）就记下来，等 [HostListener.onCallReceived] 到了再做（[PendingCallAction]）。
     */
    fun applyNotificationAction(callId: String, accept: Boolean) {
        log.i("rtc_notification_action", "callId" to callId, "accept" to accept)
        if (ringingCallId == callId) {
            main.post { act(accept) }
            return
        }
        PendingCallAction.request(callId, accept, System.currentTimeMillis())
    }

    /** 回到前台（MainActivity）：通话提醒全清——正在响的那通由 Kit 的来电界面接手，其余（未接 / 已结束）人已经在 App 里了。 */
    fun onForeground() {
        val ctx = appContext ?: return
        CallNotifications.clearAll(ctx)
    }

    private fun act(accept: Boolean) {
        val e = engine ?: return
        if (accept) e.accept { _, err -> if (err != null) log.w("rtc_auto_accept_failed", "code" to err.code) }
        else e.reject { _, err -> if (err != null) log.w("rtc_auto_reject_failed", "code" to err.code) }
    }

    private val _inCall = MutableStateFlow(false)

    /**
     * 正在音视频通话中（来电响铃 / 拨出中 到 挂断之间）。**通知判定 `alertDecision` 的 `inCall`
     * 输入读它**（NOTIFICATIONS_DESIGN §3.1："正在音视频通话 → 不响不振，会抢通话音频"）。
     * 从响铃（[IMCallEngineListener.onCallReceived] / 接通 [IMCallEngineListener.onCallBegin]）
     * 到 [IMCallEngineListener.onCallEnd] 之间恒为真——不追踪响铃与接通之间更细的子状态，
     * P0 只需要"是不是在通话"这一个粗粒度布尔。
     */
    val inCall: StateFlow<Boolean> = _inCall.asStateFlow()

    /** 引擎已建好（不代表握手已成功，连接态看日志）。 */
    val isStarted: Boolean get() = engine != null

    /**
     * 进入主界面调用。配置不全或 uid 不合规只记日志，入口点击时会给出原因。
     *
     * **同一账号同一设备重复调用是空操作**：宿主 Activity 重建（转屏 / 被系统回收再起）时界面状态会重跑一遍
     * 「进入主界面」，这时不能把正在进行的通话连引擎一起销毁。
     *
     * `rtcApi` 来自调用方持有的 `IMClient.rtc`——换票要用它，本端不持有全局单例。
     */
    fun start(
        context: Context,
        uid: String,
        deviceId: String,
        rtcApi: RtcApi,
        profiles: RtcProfileResolver? = null,
        invites: com.imrtc.uikit.IMInviteMemberProvider? = null,
        config: RtcConfig = RtcConfig.fromBuild(),
    ) {
        if (engine != null && this.uid == uid && this.deviceId == deviceId) return
        stop()
        if (!config.isUsable) {
            log.w("rtc_disabled", "missing" to config.missing.joinToString(","))
            return
        }
        RtcIds.problem("uid", uid)?.let { log.w("rtc_disabled", "reason" to it); return }
        RtcIds.problem("device_id", deviceId)?.let { log.w("rtc_disabled", "reason" to it); return }

        installSdkLog()
        tokenFetchFailed = false
        val ctx = context.applicationContext
        appContext = ctx
        this.uid = uid
        this.deviceId = deviceId
        val gen = generation
        val instance = IMCallEngine(
            IMCallEngine.Config(url = config.wsUrl, deviceId = deviceId),
            // Kit 包一层：宿主的 listener 照常收到全部回调，Kit 只是搭个便车。
            IMCallKit.wrap(HostListener(gen, rtcApi, config)),
            // 采集画质 1080p：换档位要换适配器实例（即重登），不能通话中改。
            IMWebRTCAdapter(ctx, IMVideoProfile.P1080),
        )
        engine = instance
        // IMCallKit.start 必须在 login 之前。
        profileResolver = profiles
        inviteProvider = invites
        profiles?.open()
        // 名字与头像由宿主注入（im-rtc 只认 uid）；没注入就退化成显示 uid。
        IMCallKit.start(ctx, instance, IMCallKitConfig().apply {
            profileResolver = profiles
            inviteMemberProvider = invites
            locale = imLocaleOf(LanguageStore.resolved)
        })
        log.i("rtc_start", "uid" to uid, "url" to config.wsUrl)
        scope.launch {
            val token = signToken(rtcApi)
            // 换票是异步网络请求：这段时间里可能又 stop 了（登出/切账号），generation 变了就不该
            // 再对一个已经被销毁的 engine 发 login（同 HostListener 的 stale 判定同一个思路）。
            if (gen != generation) return@launch
            if (token == null) {
                // 换票失败：引擎已经建好但从未登录过，必须回收——否则 isStarted 会一直是 true，
                // 且 start() 顶部「同账号同设备重复调用是空操作」的幂等判断会挡住下次重试，
                // 通话功能会卡死到下次账号切换 / 重启 App 为止。
                tokenFetchFailed = true
                stop()
                return@launch
            }
            instance.login(token) { _, error ->
                if (error != null) log.w("rtc_login_failed", "code" to error.code, "name" to error.name)
            }
        }
    }

    /**
     * 语言设置页选择变化时调用：Kit 挂着时立即生效，不用等下次 [start]（重登）。
     * `IMCallKit.config` 是 [start] 传入的同一个 [IMCallKitConfig] 实例（反编译 `call-uikit-2.1.0-api.jar`
     * 确认过），改它的 `locale` 字段不需要重建 Kit——与 iOS `IMRtcCall.m` 的 `_kit.config.locale =` 同一手法。
     */
    fun updateLocale() {
        if (!isStarted) return
        IMCallKit.config.locale = imLocaleOf(LanguageStore.resolved)
    }

    private fun imLocaleOf(language: ResolvedLanguage): IMLocale =
        if (language == ResolvedLanguage.EN) IMLocale.EN else IMLocale.ZH_CN

    /** 群资料页加载成员时顺手喂给通话（群通话按群成员表取名字与头像）；通话服务没起来时是空操作。 */
    fun onGroupMembers(convId: String, members: List<com.libeyond.imandroid.sdk.api.GroupMember>) {
        profileResolver?.putMembers(convId, members.map {
            RtcProfileSources.MemberRow(it.userId, it.groupNickname, it.nickname, it.username, it.avatarUrl)
        })
    }

    /** 离开主界面调用。幂等。 */
    fun stop() {
        generation++
        _inCall.value = false
        profileResolver?.close()
        val old = engine ?: return
        engine = null
        old.destroy()
        IMCallKit.stop()
        log.i("rtc_stop", "uid" to uid)
    }

    /** 单聊一对一通话。返回 null 表示已交给 Kit；否则是给用户看的原因。 */
    fun placeSingle(peerUid: String, video: Boolean): String? {
        unavailableReason()?.let { return it }
        RtcIds.problem(Str.s(R.string.rtc_kind_peer_id), peerUid)?.let { return it }
        profileResolver?.groupId = ""
        IMCallKit.placeCall(listOf(peerUid), mediaType(video), isGroup = false)
        // 拨出即算「通话中」：否则对方响铃期间来的消息会在本端外拨界面上叠一声提示音（/code-review 2026-09-29）；
        // 由 onCallEnd / stop() 复位
        _inCall.value = true
        return null
    }

    /**
     * 群通话：`chatGroupId` 是 IM 的群号，`calleeUids` 是选中的成员（不含自己）。
     * **以视频通话发起**：群通话里摄像头默认关（Kit 的 `defaultCameraOn`），且只有视频通话才有摄像头按钮。
     */
    fun placeGroup(chatGroupId: String, calleeUids: List<String>): String? {
        unavailableReason()?.let { return it }
        RtcIds.problem(Str.s(R.string.rtc_kind_group_id), chatGroupId)?.let { return it }
        if (calleeUids.isEmpty()) return Str.s(R.string.rtc_error_no_callees)
        profileResolver?.groupId = chatGroupId
        IMCallKit.placeCall(
            calleeUids, mediaType(video = true),
            IMCallOptions(isGroup = true, chatGroupId = chatGroupId),
        )
        // 拨出即算「通话中」：否则对方响铃期间来的消息会在本端外拨界面上叠一声提示音（/code-review 2026-09-29）；
        // 由 onCallEnd / stop() 复位
        _inCall.value = true
        return null
    }

    /**
     * 查自己的通话记录（设置 ▸ 最近通话），原样透传给 engine（游标分页，见 [IMCallEngine.fetchCallHistory]）。
     * 引擎没起来时直接回退失败，原因走 [unavailableReason]（与 [placeSingle]/[placeGroup] 同一套文案）。
     * 主线程回调。
     */
    fun fetchCallHistory(limit: Int, cursor: Long?, onResult: (IMCallHistoryPage?, IMRTCError?) -> Unit) {
        val e = engine
        if (e == null) {
            onResult(null, IMRTCError(0, "rtc_not_started", unavailableReason().orEmpty(), ""))
            return
        }
        e.fetchCallHistory(limit, cursor) { page, error -> onResult(page, error) }
    }

    /**
     * SDK 自己的日志默认没有出口（一条都不输出）。转给宿主的 [IMLog]：logcat 能看到，Debug 构建还会回传到
     * IMServer 的 `/__devlog`。事件名用 SDK 的 tag（稳定、可 grep），正文放 `msg`。幂等。
     */
    private fun installSdkLog() {
        IMRTCLog.setMinLevel(IMRTCLog.Level.DEBUG)
        IMRTCLog.setSink { level, tag, message ->
            val out = IMLog.tag("IM.RtcSdk")
            when (level) {
                IMRTCLog.Level.DEBUG -> out.d(tag, "msg" to message)
                IMRTCLog.Level.INFO -> out.i(tag, "msg" to message)
                IMRTCLog.Level.WARN -> out.w(tag, "msg" to message)
                IMRTCLog.Level.ERROR -> out.e(tag, null, "msg" to message)
            }
        }
    }

    private fun mediaType(video: Boolean) = if (video) "video" else "audio"

    // 联调期专用诊断：local.properties 缺配置只会在开发机上出现，不译（同 IMLog 只给开发看的口径）。
    private fun unavailableReason(): String? = when {
        engine != null -> null
        tokenFetchFailed -> Str.s(R.string.rtc_error_token_fetch_failed)
        !RtcConfig.fromBuild().isUsable ->
            "通话未配置：local.properties 缺 " + RtcConfig.fromBuild().missing.joinToString("、")
        else -> Str.s(R.string.rtc_error_not_started)
    }

    /**
     * 票的唯一来源：调 IMServer 代为向 im-rtc-server 换票，本端不需要也不该知道任何签名密钥。
     * `rtcApi` 内部的 `HttpClient` 已经带着当前 IM 会话的 Bearer token（跟项目里其它业务接口
     * 同一套鉴权），换票失败（未登录 / 未配置 / 网络异常）只记日志、返回 null——调用方据此把
     * 通话入口当"不可用"静默处理，不打扰主流程。
     */
    private suspend fun signToken(rtcApi: RtcApi): String? {
        val result = try {
            Result.success(rtcApi.fetchToken())
        } catch (e: CancellationException) {
            throw e // 协程取消必须透传，裸 catch(Exception) 会把它也吞掉（本仓 runCatchingCancellable 同款教训）。
        } catch (e: Exception) {
            Result.failure(e)
        }
        val token = rtcTokenFrom(result)
        if (token == null) {
            when (val err = result.exceptionOrNull()) {
                is ApiException -> log.w("rtc_sign_failed", "code" to err.code, "msg" to err.message)
                null -> log.w("rtc_sign_failed", "reason" to "响应缺少 token")
                else -> log.w("rtc_sign_failed", "err" to err.javaClass.simpleName, "msg" to (err.message ?: ""))
            }
        }
        return token
    }

    private class HostListener(
        private val gen: Long,
        private val rtcApi: RtcApi,
        private val config: RtcConfig,
    ) : IMCallEngineListener {
        private val stale: Boolean get() = gen != generation

        override fun onConnected(sessionId: String, resumed: Boolean) {
            if (!stale) log.i("rtc_connected", "session" to sessionId, "resumed" to resumed)
        }

        /** 来电：只记下这通是不是群通话、哪个群，好让解析器读对的成员表（不发任何请求）。 */
        override fun onCallReceived(
            callId: String, caller: String, inviter: String, calleeIds: List<String>, joinedIds: List<String>,
            mediaType: String, isGroup: Boolean, chatGroupId: String, userData: String,
        ) {
            if (stale) return
            profileResolver?.groupId = if (isGroup) chatGroupId else ""
            _inCall.value = true
            ringingCallId = callId
            // App 在前台：SDK 的来电界面接手了，通知栏里那条离线推送的来电横幅（如果有）就多余了。
            // 在后台：系统不让弹来电界面，横幅是唯一入口，留着、只静音（Kit 已经在响）。
            appContext?.let { if (AppActive.current) CallNotifications.cancel(it, callId) else CallNotifications.silence(it, callId) }
            // 用户是点着横幅上的按钮把 App 拉起来的：来电一到就替他接 / 拒。晚一拍执行，让 Kit 先把来电界面立起来。
            PendingCallAction.consume(callId, System.currentTimeMillis())?.let { accept -> main.post { act(accept) } }
        }

        /** 接通（主被叫都抛）：响铃阶段 [onCallReceived] 已经置过一次，这里覆盖同一个值，兜住主叫自己发起、没经过 onCallReceived 的路径。 */
        override fun onCallBegin(
            callId: String, roomId: String, mediaType: String, isGroup: Boolean,
            role: String, caller: String, chatGroupId: String, userData: String,
        ) {
            if (stale) return
            _inCall.value = true
            ringingCallId = null
        }

        /** 每通电话都会到达（不分角色）：只转发信号给 [onCallEnded]，不带数据——那是通话记录专属的落库判定。 */
        override fun onCallEnd(callId: String, reason: IMCallEndReason, durationSec: Long, endedBy: String) {
            if (stale) return
            _inCall.value = false
            ringingCallId = null
            appContext?.let { CallNotifications.cancel(it, callId) }
            onCallEnded?.invoke()
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
            if (plan != null) onCallRecord?.invoke(plan)
        }

        override fun onDisconnected(code: Int, willReconnect: Boolean) {
            if (!stale) log.w("rtc_disconnected", "code" to code, "reconnect" to willReconnect)
        }

        override fun onKickedOut(reason: IMKickedOutReason) {
            if (stale) return
            log.w("rtc_kicked_out", "reason" to reason.name)
            val ctx = appContext ?: return
            when (reason) {
                // 票不好使：本机再换一张重来，用户无感。**必须先 stop()**：start() 顶部「同账号同
                // 设备重复调用是空操作」的幂等判断只看 engine 是否非空，这里 engine 还在（没人调过
                // stop），不先清掉的话下面这次 start() 会被当成空操作直接跳过，换票重登永远不会发生。
                IMKickedOutReason.AUTH_EXPIRED -> main.post {
                    stop()
                    start(ctx, uid, deviceId, rtcApi, profileResolver, inviteProvider, config)
                }
                // 别处登录 / 被吊销 / 参数被拒：换票救不了，也不自动重连，停下来等人看日志。
                IMKickedOutReason.TAKEN_OVER, IMKickedOutReason.CONFIG_REJECTED -> main.post { stop() }
            }
        }

        override fun onTokenWillExpire(expiresAtMs: Long) {
            if (stale) return
            // 下一次重连生效，不打断当前通话。换票是异步的，回来时可能已经 stop 过（同上方
            // start 的 generation 判定）。
            scope.launch {
                val token = signToken(rtcApi) ?: return@launch
                if (stale) return@launch
                engine?.updateToken(token, 0L)
                log.i("rtc_token_renewed")
            }
        }

        override fun onError(code: Int, name: String, message: String, forType: String) {
            if (!stale) log.w("rtc_error", "code" to code, "name" to name, "for" to forType, "msg" to message)
        }
    }
}

/**
 * 从 `POST /api/v1/rtc/token` 的换票结果推导最终可用的 token：失败或响应缺 token 都是 null。
 *
 * **故意是包级顶层函数，不放进 `object RtcCall`**：`RtcCall` 的类初始化里有
 * `Handler(Looper.getMainLooper())`，纯 JVM 单测环境（没有 Robolectric）下访问 `RtcCall`
 * 的任何成员都会触发该初始化并抛异常——这个纯函数要被单测覆盖，必须避开那条路径。
 */
internal fun rtcTokenFrom(result: Result<RtcTokenResult>): String? =
    result.getOrNull()?.token?.takeIf { it.isNotEmpty() }
