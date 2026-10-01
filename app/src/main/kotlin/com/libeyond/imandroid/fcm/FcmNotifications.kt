package com.libeyond.imandroid.fcm

import android.app.NotificationManager
import android.content.Context
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 通知栏里那条 FCM 通知的「收回」：消息被撤回 / 为所有人删除后，别让原文继续挂在通知栏里
 * （`../../IMServer/docs/design/PUSH_M5_DESIGN.md` §3.4）。
 *
 * 两条路都会调到这里，做的是同一件事：
 * - App 不在线：服务端补发一条 `type=retract` 的 FCM（[FcmMessagingService]）；
 * - App 在线：`msg_op` 帧（实时或 sync 补拉）落库时（`data/MessageRepository.applyMsgOp`）。
 *
 * **与 iOS 的差异**：iOS 在 App 被杀时删不掉已展示的通知，只能把文字原地换成「对方撤回了一条消息」；
 * Android 能直接取消，所以这里是真的拿掉，不留提示。
 *
 * **已读清通知**（[clearReadThrough]，PUSH_M5_DESIGN §3.5）同理三条路：服务端 `type=clear`（App 不在线）、
 * 本人其它端的 receipt 帧（`MessageRepository.applyPeerReceipt`）、本机读过（`MessageRepository.markRead`）。
 * Android 的桌面角标跟着通知走，通知没了角标也就没了。
 */
object FcmNotifications {

    /** 通知 extras 里记着「这条通知展示的是哪条消息」，收回时据此比对。 */
    const val EXTRA_CONV_SEQ = "im_conv_seq"

    /** 配合 tag（= convId）使用：同一会话的新消息替换旧通知，不同会话互不影响。 */
    const val NOTIFICATION_ID = 1

    private val log = IMLog.tag("IM.Fcm")
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 该不该取消。同一会话只留一条通知（新消息替换旧的），所以**只有正挂着的恰好是被收回的那条**才取消——
     * 被收回的是更早的一条时，通知栏里展示的是后来的消息，不能动。
     * 任一侧不知道是哪条（旧版本发的通知没带 seq / 服务端没带）就不取消：宁可多留一条，不错杀。
     */
    fun shouldCancel(displayedSeq: Long?, retractedSeq: Long?): Boolean =
        displayedSeq != null && retractedSeq != null && retractedSeq > 0 && displayedSeq == retractedSeq

    /**
     * 读到 [readUpTo] 之后该不该取消。挂着的那条是位点之后来的（读的同时又来了新消息）就留着；
     * 不知道挂的是哪条（旧版本发的通知没带 seq）也留着，同 [shouldCancel]。
     */
    fun shouldClearOnRead(displayedSeq: Long?, readUpTo: Long?): Boolean =
        displayedSeq != null && readUpTo != null && readUpTo > 0 && displayedSeq <= readUpTo

    fun retract(convId: String, convSeq: Long?) =
        cancelIf(convId, "fcm_notification_retracted", convSeq) { shouldCancel(it, convSeq) }

    fun clearReadThrough(convId: String, readUpTo: Long?) =
        cancelIf(convId, "fcm_notifications_read_cleared", readUpTo) { shouldClearOnRead(it, readUpTo) }

    private fun cancelIf(convId: String, event: String, seq: Long?, decide: (displayed: Long?) -> Boolean) {
        val nm = appContext?.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            val shown = nm.activeNotifications.firstOrNull { it.tag == convId && it.id == NOTIFICATION_ID } ?: return
            val extras = shown.notification.extras
            val displayed = if (extras.containsKey(EXTRA_CONV_SEQ)) extras.getLong(EXTRA_CONV_SEQ) else null
            if (!decide(displayed)) return
            nm.cancel(convId, NOTIFICATION_ID)
            log.i(event, "convId" to convId, "convSeq" to seq)
        }.onFailure { log.w("fcm_cancel_failed", "event" to event, "err" to it.javaClass.simpleName) }
    }
}
