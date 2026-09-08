package com.libeyond.imandroid.sdk

import android.content.Context
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.sdk.api.AuthApi
import com.libeyond.imandroid.data.MessageRepository
import com.libeyond.imandroid.data.MessageService
import com.libeyond.imandroid.data.PresenceStore
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.IMDatabase
import com.libeyond.imandroid.sdk.api.ContactApi
import com.libeyond.imandroid.sdk.api.GroupApi
import com.libeyond.imandroid.sdk.api.UploadApi
import com.libeyond.imandroid.sdk.api.ConversationsApi
import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.data.DownloadSettings
import com.libeyond.imandroid.data.MediaCache
import com.libeyond.imandroid.data.MediaDownloader
import com.libeyond.imandroid.data.ThumbBackfill
import com.libeyond.imandroid.sdk.api.DevicesApi
import com.libeyond.imandroid.sdk.api.DownloadSettingsApi
import com.libeyond.imandroid.sdk.api.ProfileApi
import com.libeyond.imandroid.sdk.api.QrApi
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.http.HttpClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.session.DeviceIdentity
import com.libeyond.imandroid.sdk.session.RestoreOutcome
import com.libeyond.imandroid.sdk.session.SessionStore
import com.libeyond.imandroid.sdk.session.TokenSession
import com.libeyond.imandroid.sdk.ws.IMSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow

/**
 * SDK 门面——把 HTTP / 会话 / WebSocket 接在一起，供 UI 使用。
 * 对应 Web 的 `IMClient`、iOS 的 `IMSocketManager` + `IMHTTPService` 组合。
 *
 * **UI 只跟这个类打交道**，不直接摸 OkHttp / SharedPreferences / 协议帧。
 */
class IMClient(context: Context) {

    private val log = IMLog.tag("IM.Client")
    val scope = CoroutineScope(SupervisorJob())

    private val session = SessionStore(context)
    private val device = DeviceIdentity(context)

    private val http = HttpClient(
        host = session.host.ifEmpty { BuildConfig.DEFAULT_HOST },
        useTls = BuildConfig.USE_TLS,
        tokenProvider = { session.token },
    )

    private val auth = AuthApi(http, device)
    /** 已登录设备。**公开**：既是 [TokenSession] 的探活接口，也是「我」页设备管理页的数据源。 */
    val devices = DevicesApi(http)
    val conversationsApi = ConversationsApi(http)
    val contacts = ContactApi(http)
    val groups = GroupApi(http)
    val profile = ProfileApi(http)
    val qr = QrApi(http)
    /** 上传。**公开**：除消息媒体外，改头像也要用它（「我」页编辑资料）。 */
    val upload = UploadApi(http) { session.token }
    val downloadSettingsApi = DownloadSettingsApi(http)

    /**
     * 已下载媒体的落盘 + 下载编排（M4-7）。
     *
     * 放在 [IMClient] 而不是各页自己 new：气泡 / 宫格 / 文件 / 详情四处必须共用**同一份**
     * 在途状态，各建一个的话同一条媒体会被下两遍、进度各显各的。
     */
    val mediaCache = MediaCache(java.io.File(context.filesDir, "media"))
    val downloads = MediaDownloader(
        scope = scope,
        cache = mediaCache,
        absolute = { url -> com.libeyond.imandroid.data.MediaUrl.absolute(url, http.host, http.useTls) },
        tokenProvider = { session.token },
    )

    /**
     * 自动下载策略。**进程内缓存一份**——它每渲染一格媒体都要读一次，
     * 每次都去问服务端是不可能的。登录后拉一次，`capabilities_update` 到了重拉。
     */
    @Volatile
    var downloadSettings: DownloadSettings = DownloadPolicy.defaults()
        private set

    suspend fun refreshDownloadSettings() {
        runCatching { downloadSettingsApi.get() }
            .onSuccess { (_, s) -> downloadSettings = s }
            // 拉不到就按出厂默认走（DownloadPolicy.defaults 与服务端 Defaults 逐字对齐），
            // **不是全关**——全关会让所有图片都要手点，比策略稍微不准糟得多
            .onFailure { log.w("download_settings_fetch_failed") }
    }

    suspend fun saveDownloadSettings(s: DownloadSettings) {
        downloadSettings = s
        runCatching { downloadSettingsApi.put(s) }
            .onSuccess { (_, saved) -> downloadSettings = saved }
            .onFailure { log.w("download_settings_save_failed") }
    }

    suspend fun resetDownloadSettings() {
        runCatching { downloadSettingsApi.reset() }
            .onSuccess { (_, saved) -> downloadSettings = saved }
            .onFailure { log.w("download_settings_reset_failed") }
    }

    private val db = IMDatabase.get(context)
    val repo = MessageRepository(db.messages(), db.pending(), db.conversations())

    /**
     * 老消息补种缩略（原图已在本地时自己算一张）。**只补本机、不上行、不联网**——
     * 见 [ThumbBackfill] 的注释。
     */
    val thumbBackfill = ThumbBackfill(repo, mediaCache)

    val presence = PresenceStore()

    val tokens = TokenSession(session, auth, probe = { devices.probe() })

    /**
     * 服务器地址。debug 默认 `10.0.2.2:8080`（模拟器指向宿主机）。
     * **与凭据一起持久化**——只存 token 不存 host，重启后会拿着上一台服务器的凭据
     * 去连默认地址（/code-review 2026-09-07）。
     */
    var host: String = session.host.ifEmpty { BuildConfig.DEFAULT_HOST }
        set(v) {
            field = v
            session.host = v
            http.host = v
            socket.host = v
        }

    val socket = IMSocketManager(
        scope = scope,
        host = session.host.ifEmpty { BuildConfig.DEFAULT_HOST },
        useTls = BuildConfig.USE_TLS,
        tokenProvider = { session.token },
    )

    /** 收发编排。UI 通过它发消息、通过 [repo] 读库。 */
    val messages = MessageService(
        scope = scope,
        socket = socket,
        repo = repo,
        presence = presence,
        conversationsApi = conversationsApi,
        upload = upload,
        ownerProvider = { session.uid },
    )

    /**
     * 好友关系变更。**收到任意 friend 帧即重新拉 `/friends`**，
     * `event` 只作语义/日志（PROTOCOL §6.5）——按 event 分类型增量更新是自找麻烦，
     * 服务端也明说了这么用。
     */
    val friendEvents: SharedFlow<Unit> get() = messages.friendEvents

    /**
     * 为一个还没有会话行的对端造一个「会话壳」，让 UI 能直接进聊天页。
     *
     * conv_id 用**字典序** `u_a_u_b`——与后端 `p2pConvID`、iOS `IMConversationID`、
     * Web `convIdFor` 同构。**不能按数值排序**：uid 是 10 位随机数字，
     * 但系统账号 777000 只有 6 位，数值序与字典序在这里给出不同结果
     * （实测 `u_5205766476_u_777000` 就是字典序）。
     */
    fun conversationStubFor(peerUid: String, title: String, avatarUrl: String): ConversationEntity {
        val me = session.uid.orEmpty()
        val (a, b) = if (me <= peerUid) me to peerUid else peerUid to me
        return ConversationEntity(
            ownerUid = me,
            convId = "u_${a}_u_$b",
            isGroup = false,
            peerUid = peerUid,
            title = title,
            avatarUrl = avatarUrl,
        )
    }

    /**
     * 群会话的占位行（通讯录「群聊」列表里点一个**没聊过**的群时用）。
     *
     * 与 [conversationStubFor] 同一用途：让聊天页先有一行可渲染的会话，
     * 真正的行由首次同步覆盖。群的 conv_id 由服务端给（不像单聊能本地推），所以这里必须传进来。
     */
    fun groupConversationStubFor(convId: String, title: String, avatarUrl: String): ConversationEntity =
        ConversationEntity(
            ownerUid = session.uid.orEmpty(),
            convId = convId,
            isGroup = true,
            peerUid = "",
            title = title,
            avatarUrl = avatarUrl,
        )

    /** 服务端否定了这条会话（被踢 / 被封）。UI 订阅它回登录页。 */
    val sessionEnded: SharedFlow<com.libeyond.imandroid.sdk.ws.SessionEndReason> get() = socket.sessionEnded

    val uid: String? get() = session.uid
    val username: String? get() = session.username

    /**
     * 我的**公开显示名**——会被写进发出去的字节的地方一律用它（转发溯源、合并转发条目名…）。
     *
     * 三条纪律（IMServer docs/UI.md 隐私红线 + im-web useForward.ts 的事故记录）：
     * ① 绝不能带备注：备注只在本机渲染成立；
     * ② 绝不能写死「我」：那是**看的人**才成立的称呼，而这串字会烧进发出去的内容
     *    （im-web 2026-09-05 实测：收件人看到一排「我」）；
     * ③ 末级不落 uid——10 位随机内部 ID 摆在「转发自」后面既难看也无意义。
     *
     * **当前限制**：本端还没缓存自己的昵称（ 不下发，也没做自身资料的自取），
     * 所以取的是 。句柄是公开的，纪律不破，只是不如昵称好看。
     * 接自身资料缓存后改成「昵称 → @句柄 → 未命名用户」，与 im-web  对齐。
     */
    fun myPublicName(): String {
        val u = session.username
        return if (u.isNullOrBlank()) "未命名用户" else "@$u"
    }
    val isLoggedIn: Boolean get() = session.isLoggedIn

    init {
        messages.start()

        // 会话被服务端否定 → 清本地凭据。**不在这里跳 UI**（那是 UI 的事），
        // 但必须清凭据，否则下次冷启动又拿着一枚已死的 token 去探活。
        scope.launch {
            socket.sessionEnded.collect {
                log.w("session_ended_clearing_credentials", "reason" to it.name)
                session.clear()
                IMLog.currentUid = "-"
            }
        }
    }

    /** 冷启动恢复会话。 */
    suspend fun restore(): RestoreOutcome = tokens.restore()

    /** 账号密码登录。成功后立即连 WS。 */
    suspend fun login(username: String, password: String?) {
        val r = auth.login(username, password)
        tokens.adopt(r.token, r.uid, r.refreshToken, username)
        log.i("login_ok", "uid" to r.uid)
        socket.connect()
    }

    /** 注册后自动登录（与 iOS/Web 一致：注册页不让用户再输一遍）。 */
    suspend fun register(username: String, password: String, nickname: String) {
        auth.register(username, password, nickname)
        login(username, password)
    }

    /** 已有有效会话时连接。 */
    fun connect() = socket.connect()

    /**
     * 退出登录。
     *
     * **不清本地消息库**：切回同一账号时数据还在（iOS/Web 同构，单库多账号靠
     * ownerUid 隔离）。真要清是「删除账号数据」那个独立功能，不是退出登录。
     */
    suspend fun logout() {
        socket.disconnect()
        tokens.logout()
    }

    /** 网络恢复 / 回到前台。 */
    fun wake(reason: String) = socket.wake(reason)

    /**
     * 改公开句柄，并把新名**写回本地会话**。
     *
     * 写回这一步不能省：改名不吊销会话（当前 token 继续有效），但下次冷启动是拿
     * 本地存的 username 去重登的——不写回，重启后拿旧名登录直接失败（iOS 同款处理）。
     */
    suspend fun changeUsername(newName: String): UserCard {
        val card = profile.changeUsername(newName)
        session.username = card.username.ifBlank { newName }
        log.i("username_changed")
        return card
    }
}
