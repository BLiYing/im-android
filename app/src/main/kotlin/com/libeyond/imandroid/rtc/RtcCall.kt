package com.libeyond.imandroid.rtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
import com.libeyond.imandroid.data.AppActive
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.data.ResolvedLanguage
import com.libeyond.imandroid.fcm.CallNotifications
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.RtcApi
import com.libeyond.imandroid.sdk.api.RtcTokenResult
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
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
    internal val main = Handler(Looper.getMainLooper())

    /** 只用于换票这类"回调触发、需要挂起"的场景（续票 / 被踢后重签）；本身长期存活，跟随进程。 */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    internal var appContext: Context? = null
    internal var engine: IMCallEngine? = null
    internal var uid: String = ""
    internal var deviceId: String = ""
    internal var profileResolver: RtcProfileResolver? = null
    internal var inviteProvider: com.imrtc.uikit.IMInviteMemberProvider? = null

    /** 每次 start/stop 加一：旧引擎迟到的回调（stale）一律不算数，别改动新一代的状态。 */
    internal var generation = 0L

    /** 上一次 [start] 是否因换票失败而没能登录（引擎已回收）；[unavailableReason] 据此给出更准的提示。 */
    @Volatile private var tokenFetchFailed = false

    /**
     * 引擎被拆（换票失败 / 被踢）后能不能自愈重启：[start] 置 true，宿主 [stop]（登出 / 回登录页）与
     * 「别处登录 / 配置被拒」置 false——登出后不能偷偷把引擎拉起来，被顶号也不能互相踢。对应 iOS `IMRtcCall.m`
     * 的 `_uid.length > 0` 判断（iOS stop 不清 uid，这里显式记一个开关更直白）。
     */
    private var recoverable = false
    private var hostRtcApi: RtcApi? = null
    private var recovering = false
    /** 每次 [recoverEngine] 开一轮加一；[stop] 也加一，让迟到的旧一轮回调认出自己已作废。 */
    private var recoveryId = 0
    private val recoveryWaiters = mutableListOf<(Boolean) -> Unit>()

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
    @Volatile internal var ringingCallId: String? = null

    /** 那通来电是什么（自动接听前要看，见 [autoAcceptAllowed]）。 */
    @Volatile internal var ringingMedia: String = ""
    @Volatile internal var ringingIsGroup: Boolean = false

    /**
     * 来电横幅上点了「接听」/「拒绝」（MainActivity 收到 intent 时调）。这通已经在响就当场照做；
     * 还没到（App 刚被拉起、还没连上）就记下来，等 [RtcHostListener.onCallReceived] 到了再做（[PendingCallAction]）。
     */
    fun applyNotificationAction(callId: String, accept: Boolean) {
        log.i("rtc_notification_action", "callId" to callId, "accept" to accept)
        if (ringingCallId == callId) {
            main.post { act(accept) }
            return
        }
        PendingCallAction.request(callId, accept, System.currentTimeMillis())
    }

    /** SDK 此刻是不是正在响这通来电（离线推送晚到时据此决定横幅弹不弹、响不响，见 CallNotifications）。 */
    fun isRinging(callId: String): Boolean = ringingCallId == callId

    /** 回到前台（MainActivity）：通话提醒全清——正在响的那通由 Kit 的来电界面接手，其余（未接 / 已结束）人已经在 App 里了。 */
    fun onForeground() {
        val ctx = appContext ?: return
        CallNotifications.clearAll(ctx)
    }

    internal fun act(accept: Boolean) {
        val e = engine ?: return
        if (!accept) {
            e.reject { _, err -> if (err != null) log.w("rtc_auto_reject_failed", "code" to err.code) }
            return
        }
        val ctx = appContext ?: return
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!autoAcceptAllowed(mic, ringingMedia, ringingIsGroup)) {
            // App 已在前台，Kit 的来电界面就在眼前：让用户在那里接，权限 / 摄像头由 Kit 处理。
            log.i("rtc_auto_accept_skipped", "mic" to mic, "media" to ringingMedia, "group" to ringingIsGroup)
            return
        }
        e.accept { _, err -> if (err != null) log.w("rtc_auto_accept_failed", "code" to err.code) }
    }

    internal val _inCall = MutableStateFlow(false)

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
        onLoginResult: ((Boolean) -> Unit)? = null,
    ) {
        if (engine != null && this.uid == uid && this.deviceId == deviceId) { onLoginResult?.invoke(true); return }
        teardown()
        recoverable = true
        hostRtcApi = rtcApi
        if (!config.isUsable) {
            log.w("rtc_disabled", "missing" to config.missing.joinToString(","))
            onLoginResult?.invoke(false)
            return
        }
        RtcIds.problem("uid", uid)?.let { log.w("rtc_disabled", "reason" to it); onLoginResult?.invoke(false); return }
        RtcIds.problem("device_id", deviceId)?.let { log.w("rtc_disabled", "reason" to it); onLoginResult?.invoke(false); return }

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
            IMCallKit.wrap(RtcHostListener(gen, rtcApi, config)),
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
            // 再对一个已经被销毁的 engine 发 login（同 RtcHostListener 的 stale 判定同一个思路）。
            if (gen != generation) { onLoginResult?.invoke(false); return@launch }
            if (token == null) {
                // 换票失败：引擎已经建好但从未登录过，必须回收——否则 isStarted 会一直是 true，
                // 且 start() 顶部「同账号同设备重复调用是空操作」的幂等判断会挡住下次重试，
                // 通话功能会卡死到下次账号切换 / 重启 App 为止。
                tokenFetchFailed = true
                teardown()
                onLoginResult?.invoke(false)
                return@launch
            }
            instance.login(token) { _, error ->
                if (error != null) log.w("rtc_login_failed", "code" to error.code, "name" to error.name)
                onLoginResult?.invoke(error == null && gen == generation)
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

    /** 离开主界面调用（登出 / 回登录页）。幂等。之后 [recoverEngine] 不会再把引擎拉起来。 */
    fun stop() {
        recoverable = false
        hostRtcApi = null
        teardown()
        // 补救进行中登出：旧引擎已毁，登录回调可能永远不来——不在这里放行，recovering 会卡死、等待者永远悬着。
        recoveryId++
        finishRecovery(false)
    }

    private fun finishRecovery(ok: Boolean) {
        val waiters = recoveryWaiters.toList()
        recoveryWaiters.clear()
        recovering = false
        waiters.forEach { it(ok) }
    }

    /**
     * 引擎被拆后的补救：整台引擎重启（teardown → 换票 → start → login），对应 iOS `recoverEngineThen`。
     * 不能只重发 login：连接对象还在时（1101/2003）login 会被 SDK 拒，重启是对所有断链形态都成立的做法。
     * 通话中 / 来电响铃中不动（重启会挂断）；并发的多次调用共用一次重启；回调恒在主线程。
     */
    fun recoverEngine(done: (Boolean) -> Unit) {
        val ctx = appContext
        val api = hostRtcApi
        if (!recoverable || inCall.value || ringingCallId != null || ctx == null || api == null || uid.isEmpty()) { done(false); return }
        recoveryWaiters.add(done)
        if (recovering) return
        recovering = true
        val id = ++recoveryId
        log.i("rtc_restart_for_recover")
        // 必须先拆：引擎还在时 start() 对同账号同设备是空操作，「重启」会原地报成功而什么都没做（1101/2003 正是这种形态）。
        teardown()
        start(ctx, uid, deviceId, api, profileResolver, inviteProvider) { ok ->
            main.post {
                if (id != recoveryId) return@post
                if (!ok) log.w("rtc_restart_failed")
                finishRecovery(ok)
            }
        }
    }

    internal fun teardown() {
        generation++
        // 迟到的 onCallEnd 会被 generation 当成旧的丢掉，这里不清的话 isRinging 会一直以为还在响（推送来了也不出声）。
        ringingCallId = null
        _inCall.value = false
        profileResolver?.close()
        val old = engine ?: return
        engine = null
        old.destroy()
        IMCallKit.stop()
        log.i("rtc_stop", "uid" to uid)
    }

    /**
     * 引擎没起来（换票失败 / 被拆）但还能自愈时，先 [recoverEngine] 再拨；此时同步返回「正在连接」提示（用户随后看到拨出界面或 [onError]）。补救失败才走 [onError] 给出 [unavailableReason]。
     */
    private fun placeAfterRecover(onError: (String) -> Unit, place: () -> String?): String? {
        if (recoverable && (recovering || engine == null)) {
            // 重启中（引擎已新建但登录未回，engine 非空）再点：忽略（不排第二个 waiter，否则 place() 会执行两次），只回「正在连接」的提示
            if (recovering) return Str.s(R.string.rtc_connecting)
            recoverEngine { ok ->
                val err = if (ok) place() else notAvailableText()
                err?.let(onError)
            }
            // recoverEngine 同步拒绝（通话中等）时已回调完、recovering 仍为 false：不再提示「正在连接」
            return if (recovering) Str.s(R.string.rtc_connecting) else null
        }
        return place()
    }

    /** 单聊一对一通话。返回 null 表示已交给 Kit（或正在补救后异步拨出，失败走 [onError]）；否则是给用户看的原因。 */
    fun placeSingle(peerUid: String, video: Boolean, onError: (String) -> Unit = {}): String? =
        placeAfterRecover(onError) { placeSingleNow(peerUid, video) }

    private fun placeSingleNow(peerUid: String, video: Boolean): String? {
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
    fun placeGroup(chatGroupId: String, calleeUids: List<String>, onError: (String) -> Unit = {}): String? =
        placeAfterRecover(onError) { placeGroupNow(chatGroupId, calleeUids) }

    private fun placeGroupNow(chatGroupId: String, calleeUids: List<String>): String? {
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
    fun fetchCallHistory(limit: Int, cursor: Long?, onResult: (IMCallHistoryPage?, IMRTCError?) -> Unit) =
        fetchCallHistory(limit, cursor, allowRecover = true, onResult)

    /** allowRecover：引擎没起来 / SDK 报断链类错误时 [recoverEngine] 后重拉一次；只重试一次，防死循环（同 iOS `allowRelogin`）。 */
    private fun fetchCallHistory(
        limit: Int, cursor: Long?, allowRecover: Boolean,
        onResult: (IMCallHistoryPage?, IMRTCError?) -> Unit,
    ) {
        val e = engine
        if (e == null) {
            if (allowRecover) {
                recoverEngine { ok ->
                    if (ok) fetchCallHistory(limit, cursor, false, onResult) else onResult(null, notStartedError())
                }
            } else onResult(null, notStartedError())
            return
        }
        e.fetchCallHistory(limit, cursor) { page, error ->
            if (allowRecover && error != null && rtcShouldRecover(true, error.code)) {
                recoverEngine { ok ->
                    if (ok) fetchCallHistory(limit, cursor, false, onResult) else onResult(page, error)
                }
            } else onResult(page, error)
        }
    }

    private fun notStartedError() = IMRTCError(0, "rtc_not_started", notAvailableText(), "")

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

    /** 补救失败后给用户的话：登录失败时引擎仍在（[unavailableReason] 为 null），必须有兜底文案，否则点了拨打毫无反应。 */
    private fun notAvailableText(): String = unavailableReason() ?: Str.s(R.string.rtc_error_not_started)

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
    internal suspend fun signToken(rtcApi: RtcApi): String? {
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

/** 断链类错误码：2007 notLoggedIn / 1101 tokenInvalid / 2003 networkUnreachable（同 iOS `IMRtcHistoryErrorNeedsRelogin`）。 */
private val RTC_RECOVERABLE_CODES = setOf(2007, 1101, 2003)

/**
 * 要不要整台引擎重启：引擎没起来（被拆）一律要；起着的只在断链类错误码时要。
 * 纯函数、包级——理由同 [rtcTokenFrom]（`RtcCall` 单测里碰不得）。
 */
internal fun rtcShouldRecover(engineStarted: Boolean, errorCode: Int?): Boolean =
    !engineStarted || (errorCode != null && errorCode in RTC_RECOVERABLE_CODES)
