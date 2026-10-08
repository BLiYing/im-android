package com.libeyond.imandroid.rtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMCallHistoryPage
import com.imrtc.engine.IMCallOptions
import com.imrtc.engine.IMRTCError
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMVideoProfile
import com.imrtc.engine.webrtc.IMWebRTCAdapter
import com.imrtc.uikit.IMCallKit
import com.imrtc.uikit.IMCallKitConfig
import com.imrtc.uikit.IMKitToken
import com.imrtc.uikit.IMLocale
import com.imrtc.uikit.IMTokenCallback
import com.imrtc.uikit.IMTokenProvider
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
 * - [start]：用户进入主界面（IM 已登录）时调用——建引擎、接 Kit；**登录由 Kit 负责**（`IMCallKitConfig.tokenProvider`，
 *   im-rtc 2.2.0）：Kit 取票登录、失败退避重试、拨号前补登录、续票、票失效被踢后重登，宿主不再自己 login。
 * - [stop]：退出 / 被踢 / 被封离开主界面时调用——销毁引擎、断开 im-rtc。**不停的话换账号会有两条连接，服务端踢掉其中一条。**
 * - [placeSingle] / [placeGroup]：业务入口调用，界面全部由 Kit 接管。
 *
 * 票从哪来只在 [signToken] 一处（交给 Kit 的 tokenProvider 调）（调 IMServer `POST /api/v1/rtc/token` 代为向 im-rtc-server
 * 换票，本端不知道任何签名密钥）。对端：iOS `IMRtcCall.m`、im-web `src/rtc/rtcEngine.ts`。
 */
object RtcCall : RtcHostBridge {

    private val log = IMLog.tag("IM.Rtc")
    private val main = Handler(Looper.getMainLooper())

    /** 只用于换票（Kit 的 tokenProvider 回调里发起挂起请求）；本身长期存活，跟随进程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var appContext: Context? = null
    private var engine: IMCallEngine? = null
    private var uid: String = ""
    private var deviceId: String = ""
    private var profileResolver: RtcProfileResolver? = null
    private var inviteProvider: com.imrtc.uikit.IMInviteMemberProvider? = null

    /** 每次 start/stop 加一：旧引擎迟到的回调（stale）一律不算数，别改动新一代的状态。 */
    private var generation = 0L

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

    /** 那通来电是什么（自动接听前要看，见 [autoAcceptAllowed]）。 */
    @Volatile private var ringingMedia: String = ""
    @Volatile private var ringingIsGroup: Boolean = false

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

    private fun act(accept: Boolean) {
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
        teardown()
        if (!config.isUsable) {
            log.w("rtc_disabled", "missing" to config.missing.joinToString(","))
            return
        }
        RtcIds.problem("uid", uid)?.let { log.w("rtc_disabled", "reason" to it); return }
        RtcIds.problem("device_id", deviceId)?.let { log.w("rtc_disabled", "reason" to it); return }

        installSdkLog()
        val ctx = context.applicationContext
        appContext = ctx
        this.uid = uid
        this.deviceId = deviceId
        val gen = generation
        val instance = IMCallEngine(
            IMCallEngine.Config(url = config.wsUrl, deviceId = deviceId),
            // Kit 包一层：宿主的 listener 照常收到全部回调，Kit 只是搭个便车。
            IMCallKit.wrap(RtcHostListener(this, gen)),
            // 采集画质 1080p：换档位要换适配器实例（即重登），不能通话中改。
            IMWebRTCAdapter(ctx, IMVideoProfile.P1080),
        )
        engine = instance
        profileResolver = profiles
        inviteProvider = invites
        profiles?.open()
        // 名字与头像由宿主注入（im-rtc 只认 uid）；没注入就退化成显示 uid。
        IMCallKit.start(ctx, instance, IMCallKitConfig().apply {
            profileResolver = profiles
            inviteMemberProvider = invites
            locale = imLocaleOf(LanguageStore.resolved)
            // 登录交给 Kit（im-rtc 2.2.0）：它决定何时取票，失败了怎么重来、拨号前补登录、续票都不用宿主管。
            tokenProvider = IMTokenProvider { done -> scope.launch { done.onTokenResult(signToken(rtcApi)) } }
        })
        log.i("rtc_start", "uid" to uid, "url" to config.wsUrl)
    }

    private fun IMTokenCallback.onTokenResult(token: String?) {
        if (token != null) onResult(IMKitToken(token), null) else onResult(null, IllegalStateException("rtc_sign_failed"))
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

    /** 离开主界面调用（登出 / 回登录页）。幂等。Kit 停下时会登出 Engine、作废在途的取票。 */
    fun stop() {
        teardown()
    }

    private fun teardown() {
        generation++
        // 迟到的 onCallEnd 会被 generation 当成旧的丢掉，这里不清的话 isRinging 会一直以为还在响（推送来了也不出声）。
        ringingCallId = null
        _inCall.value = false
        profileResolver?.close()
        val old = engine ?: return
        engine = null
        // 先停 Kit（它会登出 Engine、作废在途的取票与重试），再销毁引擎。
        IMCallKit.stop()
        old.destroy()
        log.i("rtc_stop", "uid" to uid)
    }

    /**
     * 单聊一对一通话。返回 null 表示已交给 Kit；否则是给用户看的原因。
     * 还没登上时 Kit 先补一次登录、补不上自己提示（im-rtc 2.2.0）；`onError` 留给调用方，目前不会被调。
     */
    @Suppress("UNUSED_PARAMETER")
    fun placeSingle(peerUid: String, video: Boolean, onError: (String) -> Unit = {}): String? {
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
    @Suppress("UNUSED_PARAMETER")
    fun placeGroup(chatGroupId: String, calleeUids: List<String>, onError: (String) -> Unit = {}): String? {
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
     * 先 `IMCallKit.ensureReady`：启动时没登上的话 Kit 会先补一次登录（im-rtc 2.2.0）。主线程回调。
     */
    fun fetchCallHistory(limit: Int, cursor: Long?, onResult: (IMCallHistoryPage?, IMRTCError?) -> Unit) {
        if (engine == null) { onResult(null, notStartedError()); return }
        IMCallKit.ensureReady { ready ->
            val e = engine
            if (!ready || e == null) onResult(null, notStartedError()) else e.fetchCallHistory(limit, cursor) { page, error -> onResult(page, error) }
        }
    }

    private fun notStartedError() = IMRTCError(0, "rtc_not_started", unavailableReason() ?: Str.s(R.string.rtc_error_not_started), "")

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
        !RtcConfig.fromBuild().isUsable ->
            "通话未配置：local.properties 缺 " + RtcConfig.fromBuild().missing.joinToString("、")
        else -> Str.s(R.string.rtc_error_not_started)
    }

    // —— RtcHostListener 的回调落点（RtcHostBridge）：状态全在本对象内，监听只翻译事件 ——

    override fun isStale(gen: Long): Boolean = gen != generation

    override fun onIncoming(callId: String, mediaType: String, isGroup: Boolean, chatGroupId: String) {
        profileResolver?.groupId = if (isGroup) chatGroupId else ""
        _inCall.value = true
        ringingCallId = callId
        ringingMedia = mediaType
        ringingIsGroup = isGroup
        // App 在前台：SDK 的来电界面接手了，通知栏里那条离线推送的来电横幅（如果有）就多余了。
        // 在后台：系统不让弹来电界面，横幅是唯一入口，留着、只静音（Kit 已经在响）。
        appContext?.let { if (AppActive.current) CallNotifications.cancel(it, callId) else CallNotifications.silence(it, callId) }
        // 用户是点着横幅上的按钮把 App 拉起来的：来电一到就替他接 / 拒。晚一拍执行，让 Kit 先把来电界面立起来。
        PendingCallAction.consume(callId, System.currentTimeMillis())?.let { accept -> main.post { act(accept) } }
    }

    override fun onBegin() {
        _inCall.value = true
        ringingCallId = null
    }

    override fun onEnd(callId: String) {
        _inCall.value = false
        ringingCallId = null
        appContext?.let { CallNotifications.cancel(it, callId) }
        onCallEnded?.invoke()
    }

    override fun onRecord(plan: CallRecordPlan) { onCallRecord?.invoke(plan) }

    /** 别处登录 / 被吊销 / 参数被拒：换票救不了，也不自动重连，停下来等人看日志。 */
    override fun onFatalKickedOut() { main.post { stop() } }

    /**
     * 票的唯一来源：调 IMServer 代为向 im-rtc-server 换票，本端不需要也不该知道任何签名密钥。
     * `rtcApi` 内部的 `HttpClient` 已经带着当前 IM 会话的 Bearer token（跟项目里其它业务接口
     * 同一套鉴权），换票失败（未登录 / 未配置 / 网络异常）只记日志、返回 null——Kit 据此退避重试，
     * 拨号时补不上由 Kit 提示用户。
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
