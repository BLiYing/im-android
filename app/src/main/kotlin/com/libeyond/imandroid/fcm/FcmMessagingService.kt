package com.libeyond.imandroid.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.libeyond.imandroid.IMApp
import com.libeyond.imandroid.MainActivity
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.launch

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
 * **撤回/删除收回通知**：服务端对已推送过的消息补发 `type=retract`，这里不展示、只把通知栏里
 * 那条取消掉（[FcmNotifications]）；App 在线时同一件事由 `msg_op` 落库触发。
 *
 * **已知限制**：① `conv_seq` 不用于"打开会话后跳到那条消息"（见 [FcmPayload] 类注释，是有意不做）；
 * ② 没有做「App 前台时点开对应会话即清空该会话通知」这类更细的联动，只在展示时用 `conv_id`
 * 分组、`setAutoCancel` 保证点开即消。系统通知权限（Android 13+ `POST_NOTIFICATIONS`）在进主界面时
 * 申请，见 `ui/AppRoot.kt`。
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
        log.i("fcm_message_received", "convId" to content.convId, "convSeq" to content.convSeq, "retract" to content.retract)
        if (content.retract) {
            FcmNotifications.retract(content.convId, content.convSeq)
            return
        }
        showNotification(content)
    }

    private fun showNotification(content: FcmNotificationContent) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 每个会话一个不同的 data：PendingIntent 的"是不是同一个"只看 Intent.filterEquals
            // （action/data/type/component）+ requestCode，**不看 extras**。不设 data 的话只能靠
            // requestCode 区分会话，而 `convId.hashCode()` 是 32 位、会撞——撞了之后 FLAG_UPDATE_CURRENT
            // 会把先到那条通知的 extras 换成后到的，点开进错会话。
            data = Uri.Builder().scheme("imandroid").authority("conv").appendPath(content.convId).build()
            putExtra(MainActivity.EXTRA_NOTIFICATION_CONV_ID, content.convId)
            putExtra(MainActivity.EXTRA_NOTIFICATION_TITLE, content.title)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(content.title.ifBlank { Str.s(R.string.app_name) })
            .setContentText(content.body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 同一会话叠成一组（对齐服务端设计稿里 thread-id 的意图），不是每条都单独一行
            .setGroup(content.convId)
        content.badge?.let { builder.setNumber(it) }
        // 记下展示的是哪条消息：它被撤回/删除时据此收回这条通知（FcmNotifications.retract）。
        content.convSeq?.let { builder.addExtras(Bundle().apply { putLong(FcmNotifications.EXTRA_CONV_SEQ, it) }) }

        // notify() 在没有 POST_NOTIFICATIONS 权限时是静默不弹（Android 官方行为），不会抛异常；
        // 这里仍包一层防御，避免个别机型/厂商 ROM 的非标准实现意外抛出而崩整个进程。
        // 用 (tag=convId, id 固定) 标识通知：字符串 tag 不会像 32 位 hashCode 那样让两个会话互相覆盖。
        runCatching { nm.notify(content.convId, FcmNotifications.NOTIFICATION_ID, builder.build()) }
            .onFailure { log.w("fcm_notify_failed", "err" to it.javaClass.simpleName) }
    }

    /** 渠道只需建一次；`getNotificationChannel` 已存在时直接跳过，重复 `createNotificationChannel` 本身也是幂等的。 */
    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Str.s(R.string.notif_section_message), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        /** minSdk 26 = `Build.VERSION_CODES.O`，渠道 API 恒可用，不需要版本判断分支。 */
        const val CHANNEL_ID = "fcm_messages"
    }
}
