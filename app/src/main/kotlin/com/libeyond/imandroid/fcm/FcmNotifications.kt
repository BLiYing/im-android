package com.libeyond.imandroid.fcm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.toBitmap
import com.libeyond.imandroid.MainActivity
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 通知栏里每个会话的那条 FCM 通知（PUSH_M5_DESIGN §3.4 / §3.5 / §3.7）。
 *
 * **一个会话一条通知，展开看最近几条**（`MessagingStyle`，同 WhatsApp / Telegram）；锁屏隐藏内容时
 * 显示「N 条新消息」（`publicVersion`）；桌面角标数（`setNumber`）是这个会话的条数，各会话加起来就是总数。
 * 列了哪几条、累计几条存在通知自己的 extras 里（[ConversationLines]），进程被杀后 FCM 再拉起也接得上。
 *
 * 三件事都落到这里：
 * - 新消息（[showMessage]，`FcmMessagingService`）：加一行，响铃 / 横幅照常；
 * - 撤回 / 为所有人删除（[retract]）：去掉那一行；服务端 `type=retract`（App 不在线）或 `msg_op` 落库触发；
 * - 已读（[clearReadThrough]）：去掉已读的行；服务端 `type=clear`、本人其它端的 receipt 帧、本机已读触发。
 * 后两者去完还有剩就**静默**重发（不响不弹），一行不剩才取消。Android 的桌面角标跟着通知走。
 *
 * **与 iOS 的差异**：iOS 一条消息一个通知、按会话叠放，删不掉被杀进程里的通知只能原地替换文字；
 * Android 能直接改写 / 取消，所以这里是真的拿掉，不留提示。
 */
object FcmNotifications {

    /** 通知 extras 里记着「最新那条是哪条消息」（老版本只认这个；现在以下面几项为准）。 */
    const val EXTRA_CONV_SEQ = "im_conv_seq"
    private const val EXTRA_SEQS = "im_line_seqs"
    private const val EXTRA_SENDERS = "im_line_senders"
    private const val EXTRA_TEXTS = "im_line_texts"
    private const val EXTRA_TIMES = "im_line_times"
    private const val EXTRA_ALL_SEQS = "im_all_seqs"
    private const val EXTRA_TITLE = "im_conv_title"
    private const val EXTRA_GROUP = "im_conv_group"

    /** 配合 tag（= convId）使用：一个会话一条通知，不同会话互不影响。 */
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "fcm_messages"

    private val log = IMLog.tag("IM.Fcm")
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 新消息：在这个会话的通知里加一行并提醒。[avatar] 是大图标（发送人 / 群头像），可为空。 */
    fun showMessage(content: FcmNotificationContent, avatar: Bitmap?) {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val line = content.toLine(System.currentTimeMillis()) ?: return
        runCatching {
            val shown = shownFor(nm, content.convId)
            val before = shown?.let { linesOf(it.notification.extras) } ?: ConversationLines.EMPTY
            val after = before.append(line)
            if (after == before) return // 重投的同一条
            val meta = Meta(content.title, content.isGroup)
            post(ctx, nm, content.convId, meta, after, avatar ?: shown?.let { iconOf(ctx, it.notification) }, alert = true)
        }.onFailure { log.w("fcm_notify_failed", "err" to it.javaClass.simpleName) }
    }

    fun retract(convId: String, convSeq: Long?) {
        val seq = convSeq?.takeIf { it > 0 } ?: return
        rewrite(convId, "fcm_notification_retracted", seq) { it.withoutSeq(seq) }
    }

    fun clearReadThrough(convId: String, readUpTo: Long?) {
        val upTo = readUpTo?.takeIf { it > 0 } ?: return
        rewrite(convId, "fcm_notifications_read_cleared", upTo) { it.readThrough(upTo) }
    }

    /**
     * App 回到前台：清掉所有会话的消息通知（同 iOS `sceneDidBecomeActive` 的 removeAllDeliveredNotifications、
     * 同微信）。人已经在 App 里了，未读数在会话列表 / 桌面角标照样看得到，通知栏再挂着就是重复。
     * 只动本类发的那些（[CHANNEL_ID] 渠道、[NOTIFICATION_ID]），不碰来电等别的通知。
     */
    fun clearAll() {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            val ours = nm.activeNotifications.filter {
                it.id == NOTIFICATION_ID && it.tag != null && it.notification.channelId == CHANNEL_ID
            }
            ours.forEach { nm.cancel(it.tag, it.id) }
            if (ours.isNotEmpty()) log.i("fcm_notifications_cleared_on_open", "count" to ours.size)
        }.onFailure { log.w("fcm_cancel_failed", "event" to "open", "err" to it.javaClass.simpleName) }
    }

    /** 去掉若干行：没变就不动；一行不剩取消；还有剩就静默重发。 */
    private fun rewrite(convId: String, event: String, seq: Long, change: (ConversationLines) -> ConversationLines) {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            val shown = shownFor(nm, convId) ?: return
            val extras = shown.notification.extras
            val before = linesOf(extras) ?: return // 老版本发的通知：认不出是哪几条，宁可留着
            val after = change(before)
            if (after == before) return
            if (after.lines.isEmpty()) { // 显示的几行都没了（挤出去的更早那些也就不必再提醒）：整条取消
                nm.cancel(convId, NOTIFICATION_ID)
            } else {
                val meta = Meta(extras.getString(EXTRA_TITLE).orEmpty(), extras.getBoolean(EXTRA_GROUP))
                post(ctx, nm, convId, meta, after, iconOf(ctx, shown.notification), alert = false)
            }
            log.i(event, "convId" to convId, "convSeq" to seq, "left" to after.lines.size)
        }.onFailure { log.w("fcm_cancel_failed", "event" to event, "err" to it.javaClass.simpleName) }
    }

    private data class Meta(val title: String, val isGroup: Boolean)

    private fun shownFor(nm: NotificationManager, convId: String) =
        nm.activeNotifications.firstOrNull { it.tag == convId && it.id == NOTIFICATION_ID }

    private fun iconOf(ctx: Context, n: Notification): Bitmap? =
        runCatching { n.getLargeIcon()?.loadDrawable(ctx)?.toBitmap() }.getOrNull()

    private fun linesOf(extras: Bundle): ConversationLines? = ConversationLines.fromArrays(
        extras.getLongArray(EXTRA_SEQS), extras.getStringArray(EXTRA_SENDERS), extras.getStringArray(EXTRA_TEXTS),
        extras.getLongArray(EXTRA_TIMES), extras.getLongArray(EXTRA_ALL_SEQS),
    )

    private fun extrasOf(state: ConversationLines, meta: Meta) = Bundle().apply {
        putLongArray(EXTRA_SEQS, state.lineSeqs)
        putStringArray(EXTRA_SENDERS, state.lineSenders)
        putStringArray(EXTRA_TEXTS, state.lineTexts)
        putLongArray(EXTRA_TIMES, state.lineTimes)
        putLongArray(EXTRA_ALL_SEQS, state.allSeqs)
        putString(EXTRA_TITLE, meta.title)
        putBoolean(EXTRA_GROUP, meta.isGroup)
        state.lines.lastOrNull()?.let { putLong(EXTRA_CONV_SEQ, it.seq) }
    }

    private fun post(
        ctx: Context, nm: NotificationManager, convId: String, meta: Meta,
        state: ConversationLines, avatar: Bitmap?, alert: Boolean,
    ) {
        ensureChannel(nm)
        val title = meta.title.ifBlank { Str.s(R.string.app_name) }
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(Str.s(R.string.common_me)).build())
        if (meta.isGroup) style.setConversationTitle(title).setGroupConversation(true)
        state.lines.forEach { line ->
            // 单聊每行的发送人就是对方；群聊老服务端没给发送人时，正文本身带着「名字: 」，发送人用群名占位
            style.addMessage(line.text, line.time, Person.Builder().setName(line.sender.ifBlank { title }).build())
        }
        val latest = state.lines.last()
        // 锁屏隐藏内容时系统显示这个版本：只说有几条，不露发送人与内容。
        val publicVersion = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(Str.s(R.string.app_name))
            .setContentText(Str.p(R.plurals.notif_new_messages_count, state.total, state.total))
            .build()
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(if (meta.isGroup && latest.sender.isNotBlank()) "${latest.sender}: ${latest.text}" else latest.text)
            .setStyle(style)
            .setNumber(state.total)
            .setPublicVersion(publicVersion)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx, convId, meta.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(!alert) // 撤回 / 已读后的重发不响不弹
            .addExtras(extrasOf(state, meta))
        avatar?.let { builder.setLargeIcon(it) }
        // notify() 在没有 POST_NOTIFICATIONS 权限时静默不弹（官方行为）；调用方仍包了 runCatching 防个别 ROM 抛异常。
        // 用 (tag=convId, id 固定) 标识通知：字符串 tag 不会像 32 位 hashCode 那样让两个会话互相覆盖。
        nm.notify(convId, NOTIFICATION_ID, builder.build())
    }

    private fun contentIntent(ctx: Context, convId: String, title: String): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 每个会话一个不同的 data：PendingIntent 的"是不是同一个"只看 Intent.filterEquals
            // （action/data/type/component）+ requestCode，**不看 extras**。不设 data 的话只能靠
            // requestCode 区分会话，而 `convId.hashCode()` 是 32 位、会撞——撞了之后 FLAG_UPDATE_CURRENT
            // 会把先到那条通知的 extras 换成后到的，点开进错会话。
            data = Uri.Builder().scheme("imandroid").authority("conv").appendPath(convId).build()
            putExtra(MainActivity.EXTRA_NOTIFICATION_CONV_ID, convId)
            putExtra(MainActivity.EXTRA_NOTIFICATION_TITLE, title)
        }
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 渠道只需建一次；minSdk 26 渠道 API 恒可用。 */
    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Str.s(R.string.notif_section_message), NotificationManager.IMPORTANCE_HIGH),
        )
    }
}
