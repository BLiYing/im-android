package com.libeyond.imandroid.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.imrtc.engine.IMCallEngine
import com.imrtc.engine.IMCallEngineListener
import com.imrtc.engine.IMCallOptions
import com.imrtc.engine.IMDebugTokenGenerator
import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.media.IMVideoProfile
import com.imrtc.engine.webrtc.IMWebRTCAdapter
import com.imrtc.uikit.IMCallKit
import com.imrtc.uikit.IMCallKitConfig
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * im-rtc 通话的宿主侧接入点（**只有主线程调用**）。
 *
 * - [start]：用户进入主界面（IM 已登录）时调用——建引擎、本机签调试票、登录 im-rtc，此后能拨也能接。
 * - [stop]：退出 / 被踢 / 被封离开主界面时调用——销毁引擎、断开 im-rtc。**不停的话换账号会有两条连接，服务端踢掉其中一条。**
 * - [placeSingle] / [placeGroup]：业务入口调用，界面全部由 Kit 接管。
 *
 * 票从哪来（调试签票 / 后台接口）只在 [signToken] 一处，以后加开关只改这里。
 */
object RtcCall {

    private val log = IMLog.tag("IM.Rtc")
    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var engine: IMCallEngine? = null
    private var uid: String = ""
    private var deviceId: String = ""
    private var profileResolver: RtcProfileResolver? = null

    /** 每次 start/stop 加一：旧引擎迟到的回调（stale）一律不算数，别改动新一代的状态。 */
    private var generation = 0L

    /** 引擎已建好（不代表握手已成功，连接态看日志）。 */
    val isStarted: Boolean get() = engine != null

    /**
     * 进入主界面调用。配置不全或 uid 不合规只记日志，入口点击时会给出原因。
     *
     * **同一账号同一设备重复调用是空操作**：宿主 Activity 重建（转屏 / 被系统回收再起）时界面状态会重跑一遍
     * 「进入主界面」，这时不能把正在进行的通话连引擎一起销毁。
     */
    fun start(
        context: Context,
        uid: String,
        deviceId: String,
        profiles: RtcProfileResolver? = null,
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
        val ctx = context.applicationContext
        appContext = ctx
        this.uid = uid
        this.deviceId = deviceId
        val gen = generation
        val instance = IMCallEngine(
            IMCallEngine.Config(url = config.wsUrl, deviceId = deviceId),
            // Kit 包一层：宿主的 listener 照常收到全部回调，Kit 只是搭个便车。
            IMCallKit.wrap(HostListener(gen, config)),
            // 采集画质 1080p：换档位要换适配器实例（即重登），不能通话中改。
            IMWebRTCAdapter(ctx, IMVideoProfile.P1080),
        )
        engine = instance
        // IMCallKit.start 必须在 login 之前。
        profileResolver = profiles
        profiles?.open()
        // 名字与头像由宿主注入（im-rtc 只认 uid）；没注入就退化成显示 uid。
        IMCallKit.start(ctx, instance, IMCallKitConfig().apply { profileResolver = profiles })
        val token = signToken(config)
        log.i("rtc_start", "uid" to uid, "app" to config.appId, "url" to config.wsUrl)
        instance.login(token) { _, error ->
            if (error != null) log.w("rtc_login_failed", "code" to error.code, "name" to error.name)
        }
    }

    /** 群资料页加载成员时顺手喂给通话（群通话按群成员表取名字与头像）；通话服务没起来时是空操作。 */
    fun onGroupMembers(convId: String, members: List<com.libeyond.imandroid.sdk.api.GroupMember>) {
        profileResolver?.putMembers(convId, members.map {
            RtcProfileSources.MemberRow(it.userId, it.groupNickname, it.nickname, it.username, it.avatarUrl)
        })
    }

    /** 离开主界面调用。幂等。 */
    fun stop() {
        generation++
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
        RtcIds.problem("对方 id", peerUid)?.let { return it }
        profileResolver?.groupId = ""
        IMCallKit.placeCall(listOf(peerUid), mediaType(video), isGroup = false)
        return null
    }

    /**
     * 群通话：`chatGroupId` 是 IM 的群号，`calleeUids` 是选中的成员（不含自己）。
     * **以视频通话发起**：群通话里摄像头默认关（Kit 的 `defaultCameraOn`），且只有视频通话才有摄像头按钮。
     */
    fun placeGroup(chatGroupId: String, calleeUids: List<String>): String? {
        unavailableReason()?.let { return it }
        RtcIds.problem("群号", chatGroupId)?.let { return it }
        if (calleeUids.isEmpty()) return "请选择要呼叫的成员"
        profileResolver?.groupId = chatGroupId
        IMCallKit.placeCall(
            calleeUids, mediaType(video = true),
            IMCallOptions(isGroup = true, chatGroupId = chatGroupId),
        )
        return null
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

    private fun unavailableReason(): String? = when {
        engine != null -> null
        !RtcConfig.fromBuild().isUsable ->
            "通话未配置：local.properties 缺 " + RtcConfig.fromBuild().missing.joinToString("、")
        else -> "通话服务未启动（请重新登录）"
    }

    /** 票的唯一来源。联调期本机签调试票；接了后台换票接口之后这里改成调接口。 */
    private fun signToken(config: RtcConfig): String = IMDebugTokenGenerator.generateDebugToken(
        appId = config.appId, keyId = config.keyId, secret = config.secret,
        uid = uid, deviceId = deviceId,
    )

    private class HostListener(private val gen: Long, private val config: RtcConfig) : IMCallEngineListener {
        private val stale: Boolean get() = gen != generation

        override fun onConnected(sessionId: String, resumed: Boolean) {
            if (!stale) log.i("rtc_connected", "session" to sessionId, "resumed" to resumed)
        }

        /** 来电：只记下这通是不是群通话、哪个群，好让解析器读对的成员表（不发任何请求）。 */
        override fun onCallReceived(
            callId: String, caller: String, inviter: String, calleeIds: List<String>,
            mediaType: String, isGroup: Boolean, chatGroupId: String, userData: String,
        ) {
            if (stale) return
            profileResolver?.groupId = if (isGroup) chatGroupId else ""
        }

        override fun onDisconnected(code: Int, willReconnect: Boolean) {
            if (!stale) log.w("rtc_disconnected", "code" to code, "reconnect" to willReconnect)
        }

        override fun onKickedOut(reason: IMKickedOutReason) {
            if (stale) return
            log.w("rtc_kicked_out", "reason" to reason.name)
            val ctx = appContext ?: return
            when (reason) {
                // 票不好使：本机再签一张重来，用户无感。
                IMKickedOutReason.AUTH_EXPIRED -> main.post { start(ctx, uid, deviceId, profileResolver, config) }
                // 别处登录 / 被吊销 / 参数被拒：换票救不了，也不自动重连，停下来等人看日志。
                IMKickedOutReason.TAKEN_OVER, IMKickedOutReason.CONFIG_REJECTED -> main.post { stop() }
            }
        }

        override fun onTokenWillExpire(expiresAtMs: Long) {
            if (stale) return
            // 下一次重连生效，不打断当前通话。
            engine?.updateToken(signToken(config), 0L)
            log.i("rtc_token_renewed")
        }

        override fun onError(code: Int, name: String, message: String, forType: String) {
            if (!stale) log.w("rtc_error", "code" to code, "name" to name, "for" to forType, "msg" to message)
        }
    }
}
