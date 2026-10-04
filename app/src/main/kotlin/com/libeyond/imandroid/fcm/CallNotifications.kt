package com.libeyond.imandroid.fcm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.libeyond.imandroid.MainActivity
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * App 在后台 / 被杀时的通话提醒（服务端 `type=call`，PUSH_M5_DESIGN §3.8）：
 *
 * - `incoming`：来电横幅。**高优先级、来电铃声、不设全屏**（同微信国内版的普通推送 + 铃声，不做 CallKit 式全屏），
 *   [RING_TIMEOUT_MS] 后自动消失；带「接听」「拒绝」两个按钮，**都先拉起 App**——连上之后服务端才开始振铃，
 *   SDK 抛来电时再按 [com.libeyond.imandroid.rtc.PendingCallAction] 自动接听 / 拒绝。
 * - `missed`：群通话没接，原地换成一条普通的「未接来电」（走消息通知渠道的提示音）。
 * - `ended`：通话结束 / 已在别处处理，直接取消来电横幅。
 *
 * 一通电话一个通知位：tag = `call:<call_id>`，与服务端 APNs 的 `apns-collapse-id` 同值；id 固定 [NOTIFICATION_ID]，
 * 与会话消息通知（[FcmNotifications.NOTIFICATION_ID]）分开，[FcmNotifications.clearAll] 不会误伤。
 */
object CallNotifications {
    const val CHANNEL_ID = "fcm_calls"
    const val NOTIFICATION_ID = 2

    /** 来电横幅最多挂这么久（= 默认振铃时长）；服务端的 call.ended 通常更早把它取消。 */
    const val RING_TIMEOUT_MS = 30_000L

    /** MainActivity 读的两个 extra：哪通电话、点的是哪个按钮。 */
    const val EXTRA_CALL_ID = "notification_call_id"
    const val EXTRA_CALL_ACTION = "notification_call_action"
    const val ACTION_ACCEPT = "accept"
    const val ACTION_REJECT = "reject"

    private val log = IMLog.tag("IM.Fcm")

    fun tagOf(callId: String) = "call:$callId"

    fun handle(ctx: Context, content: FcmNotificationContent, avatar: Bitmap?) {
        val call = content.call ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            when (call.kind) {
                FcmCallNotice.INCOMING -> nm.notify(tagOf(call.callId), NOTIFICATION_ID, incoming(ctx, nm, content, call, avatar))
                FcmCallNotice.MISSED -> nm.notify(tagOf(call.callId), NOTIFICATION_ID, missed(ctx, nm, content, avatar))
                FcmCallNotice.ENDED -> nm.cancel(tagOf(call.callId), NOTIFICATION_ID)
                else -> log.w("fcm_call_kind_unknown", "kind" to call.kind)
            }
            log.i("fcm_call_notice", "callId" to call.callId, "kind" to call.kind)
        }.onFailure { log.w("fcm_call_notify_failed", "err" to it.javaClass.simpleName) }
    }

    /**
     * SDK 在 App **后台**收到了这通来电：Kit 已经在响铃，但系统不让后台弹来电界面
     * （真机日志 `Background activity launch blocked!`）。横幅得留着——它是用户唯一能点的入口——
     * 只把它的铃声停掉，免得 Kit 的铃声和通知铃声两路一起响。没有这条横幅就什么都不做。
     */
    fun silence(ctx: Context, callId: String) {
        if (callId.isBlank()) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            val shown = nm.activeNotifications.firstOrNull { it.tag == tagOf(callId) && it.id == NOTIFICATION_ID } ?: return
            val quiet = Notification.Builder.recoverBuilder(ctx, shown.notification).setOnlyAlertOnce(true).build()
            quiet.flags = quiet.flags and Notification.FLAG_INSISTENT.inv()
            nm.notify(tagOf(callId), NOTIFICATION_ID, quiet)
        }.onFailure { log.w("fcm_call_silence_failed", "err" to it.javaClass.simpleName) }
    }

    /**
     * App 回到前台：清掉通话提醒（未接来电、早已结束的来电横幅），同 [FcmNotifications.clearAll] 与 iOS
     * `sceneDidBecomeActive`。[ringingCallId] 是此刻还在响的那通，由调用方决定要不要留（SDK 的来电界面会接手它）。
     */
    fun clearAll(ctx: Context, except: String? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val keep = except?.let { tagOf(it) }
        runCatching {
            nm.activeNotifications
                .filter { it.id == NOTIFICATION_ID && it.tag?.startsWith("call:") == true && it.tag != keep }
                .forEach { nm.cancel(it.tag, NOTIFICATION_ID) }
        }
    }

    /** SDK 已经接手这通电话（App 在前台、来电界面弹出 / 通话结束），或用户点了横幅：收掉来电横幅。 */
    fun cancel(ctx: Context, callId: String) {
        if (callId.isBlank()) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(tagOf(callId), NOTIFICATION_ID) }
    }

    private fun incoming(
        ctx: Context, nm: NotificationManager, content: FcmNotificationContent, call: FcmCallNotice, avatar: Bitmap?,
    ): Notification {
        ensureCallChannel(nm)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(content.title.ifBlank { Str.s(R.string.app_name) })
            .setContentText(content.body)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setContentIntent(callIntent(ctx, content, call.callId, null))
            .addAction(0, Str.s(R.string.common_reject), callIntent(ctx, content, call.callId, ACTION_REJECT))
            .addAction(0, Str.s(R.string.push_call_action_answer), callIntent(ctx, content, call.callId, ACTION_ACCEPT))
        avatar?.let { builder.setLargeIcon(it) }
        // 铃声循环到用户处理 / 超时 / 服务端收回为止，像来电而不是叮一声。
        return builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    private fun missed(ctx: Context, nm: NotificationManager, content: FcmNotificationContent, avatar: Bitmap?): Notification {
        FcmNotifications.ensureMessageChannel(nm)
        val builder = NotificationCompat.Builder(ctx, FcmNotifications.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(content.title.ifBlank { Str.s(R.string.app_name) })
            .setContentText(content.body)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(callIntent(ctx, content, content.call?.callId.orEmpty(), null))
        avatar?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    /**
     * 点横幅本身（[action] 为 null）= 打开 App 进那个会话；点按钮 = 打开 App 并接听 / 拒绝。
     * 每个按钮一个不同的 data：PendingIntent 只按 Intent.filterEquals + requestCode 判同，**不看 extras**，
     * 不区分的话三个入口会互相覆盖成同一个动作。
     */
    private fun callIntent(ctx: Context, content: FcmNotificationContent, callId: String, action: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            data = Uri.Builder().scheme("imandroid").authority("call").appendPath(callId)
                .appendPath(action ?: "open").build()
            putExtra(MainActivity.EXTRA_NOTIFICATION_CONV_ID, content.convId)
            putExtra(MainActivity.EXTRA_NOTIFICATION_TITLE, content.title)
            putExtra(EXTRA_CALL_ID, callId)
            action?.let { putExtra(EXTRA_CALL_ACTION, it) }
        }
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 来电渠道：高重要性 + 系统来电铃声。渠道建好后声音由用户在系统设置里改，改代码不会生效（要换渠道 id）。 */
    private fun ensureCallChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, Str.s(R.string.push_call_channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        channel.enableVibration(true)
        nm.createNotificationChannel(channel)
    }
}
