package com.libeyond.imandroid.fcm

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.IMApp
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FCM 离线推送（M5 批次 2，`../../IMServer/docs/design/PUSH_M5_DESIGN.md` + 父任务简报：Android 走 FCM，
 * 而不是文档 §6 原定的「后台保持连接」方案——那个方案在本仓尚未开工，FCM 是并行拍板改走的路线，
 * 服务端 `internal/push` 正在并行加 FCM sender，协议形状已定死，见 [com.libeyond.imandroid.sdk.api.PushTokenApi]）。
 *
 * **只用 `data` payload，不用 `notification` 字段**：服务端组的推送内容全部放进 `data`（`title`/`body`/
 * `conv_id`/`conv_seq`/`badge`），因为要在 App 被杀死时也能自定义处理——用系统自动展示的 `notification`
 * 字段拿不到 `conv_id`，点开也没法跳转到对应会话。
 *
 * **不在客户端重复判定「该不该提醒」**：服务端已经跑过一份 `alertDecision`（账号级开关、会话免打扰、
 * @我 穿透……见设计稿 §3.1）才会把这条推送发过来，这里收到什么就展示什么。
 *
 * **与本机实时提醒的关系**：服务端只推给「没有 WebSocket 连接，或连接但报告了后台」的会话
 * （设计稿 §2.1）。本端会上报 `app_state`（`data/AppStateReport.kt`），所以 **App 切到后台、WebSocket
 * 还连着的那段时间，同一条消息会走两条路同时到达**：实时 NEW_MSG 帧 + 本类收到的 FCM。两边不会
 * 重复提醒，靠的是 `AlertDecision.decide()` 在 `!appActive` 时把应用内提醒（横幅/声音/振动）整体
 * 静掉——后台只剩本类这一条系统通知。**改动任一侧的「后台是否提醒」判据前，先确认另一侧**，
 * 否则要么后台响两次，要么两边都不响。
 *
 * **通知怎么展示**全在 [FcmNotifications]：一个会话一条、展开看最近几条、锁屏显示「N 条新消息」；
 * `type=retract`（撤回/删除）与 `type=clear`（别处已读）都是从那条通知里去掉对应的行，不展示新东西。
 * 本类只负责解析载荷、取头像。
 *
 * **已知限制**：`conv_seq` 不用于"打开会话后跳到那条消息"（见 [FcmPayload] 类注释，是有意不做）。
 * 系统通知权限（Android 13+ `POST_NOTIFICATIONS`）在进主界面时申请，见 `ui/AppRoot.kt`。
 */
class FcmMessagingService : FirebaseMessagingService() {

    private val log = IMLog.tag("IM.Fcm")

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        log.i("fcm_new_token")
        val client = (application as? IMApp)?.client ?: return
        client.scope.launch { client.fcmTokenStore.reportToken(token) }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val content = FcmPayload.parse(remoteMessage.data)
        if (content == null) {
            log.w("fcm_message_unparseable")
            return
        }
        log.i(
            "fcm_message_received", "convId" to content.convId, "convSeq" to content.convSeq,
            "retract" to content.retract, "clear" to content.clear,
        )
        val client = (application as? IMApp)?.client
        if (client != null && !client.isLoggedIn && FcmPayload.dropWhenLoggedOut(content)) {
            // 本机已登出还收到推送：不弹，并作废 token 让服务端别再推（见 dropWhenLoggedOut / FcmToken.deleteWhileLoggedOut）。
            log.w("fcm_dropped_logged_out", "call" to (content.call != null))
            FcmToken.deleteWhileLoggedOut()
            return
        }
        if (content.retract) {
            FcmNotifications.retract(content.convId, content.convSeq)
            return
        }
        if (content.clear) {
            FcmNotifications.clearReadThrough(content.convId, content.convSeq)
            return
        }
        if (content.call != null) {
            // 来电要尽快弹：收回横幅（ended）用不着头像，别为它白等一次下载。
            val avatar = if (content.call.kind == FcmCallNotice.ENDED) null else loadAvatar(content.iconAvatar)
            CallNotifications.handle(this, content, avatar)
            return
        }
        FcmNotifications.showMessage(content, loadAvatar(content.iconAvatar))
    }

    /**
     * 同步取一张圆形头像做通知大图标；取不到（没有头像地址 / 下载失败）返回 null——**不在这里兜底画
     * 占位图**：调用方 [FcmNotifications.showMessage] 还有一层更好的兜底，没取到新头像时先接着用
     * 这条会话通知**已经显示着的**头像（多半是真头像，比凭空画一张占位图更对），首字母占位图只在
     * 两层都没有时才画（见那边的 [fcmAvatarPlaceholder] 调用）。这里提前兜底会把那层挡住。
     *
     * `onMessageReceived` 跑在 FCM 的后台线程上、消息是**串行**处理的，所以这里最多等
     * [AVATAR_TIMEOUT_MS]；而且一次取失败（多半是连不上 IM 服务器）后的
     * [FcmPayload.AVATAR_NETWORK_BACKOFF_MS] 内只读 Coil 的本地缓存、不再联网——否则连发几条，
     * 每条都要白等一次，后面的通知被前面的拖着晚到。同一张头像（内容寻址 URL）缓存过的照样能显示。
     */
    private fun loadAvatar(path: String?): Bitmap? {
        val client = (application as? IMApp)?.client ?: return null
        val url = FcmPayload.avatarUrl(path, client.host, BuildConfig.USE_TLS) ?: return null
        val now = System.currentTimeMillis()
        val useNetwork = FcmPayload.avatarNetworkAllowed(lastAvatarFailureMs, now)
        val request = ImageRequest.Builder(this)
            .data(url)
            .size(FCM_AVATAR_PX)
            .transformations(CircleCropTransformation())
            .allowHardware(false) // 通知要的是软件位图，硬件位图跨进程会被拒
            .networkCachePolicy(if (useNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
        val bitmap = runCatching {
            runBlocking { withTimeoutOrNull(AVATAR_TIMEOUT_MS) { imageLoader.execute(request).drawable?.toBitmap() } }
        }.getOrNull()
        if (bitmap == null) {
            if (useNetwork) lastAvatarFailureMs = now
            log.w("fcm_avatar_load_failed", "path" to path, "network" to useNetwork)
        }
        return bitmap
    }

    companion object {
        private const val AVATAR_TIMEOUT_MS = 3_000L

        /** 最近一次联网取头像失败的时间（进程内；FCM 服务实例每条消息可能不同，所以放这里）。 */
        @Volatile private var lastAvatarFailureMs = 0L

    }
}
