package com.libeyond.imandroid.data

import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.sdk.AlertPlayer
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MessageData

/**
 * 实时入站消息 → [AlertDecision] → [AlertPlayer] 的接线（NOTIFICATIONS_DESIGN §3.1/§7）。
 *
 * **只挂在 [MessageService] 的 `NEW_MSG` 分支**——sync 补拉 / 按锚点开窗都不调这里，
 * `isLive=true` 这件事本身就是"只有这一条调用路径会喂给判定函数"，不是靠某个字段现查出来的
 * （§3.1："历史回填、离线积压、窗口加载一律不判"，D4 桌面端就是在这条上踩过"打开一个会话弹 42 条"的坑）。
 *
 * 拆成独立文件（CODING_STYLE §7③）：`MessageService.kt` 已贴近 600 行体量红线，
 * 这段是纯粹的"取上下文 → 判定 → 播放"glue，天然可以从帧分派里摘出去，不影响调用形态。
 */
object IncomingAlert {

    private val log = IMLog.tag("IM.Alert")

    suspend fun handle(owner: String, m: MessageData, repo: MessageRepository) {
        if (m.from.isEmpty() || m.from == owner) return
        // 本地还没有这个会话行（真正意义上的"第一条消息"）：拿不到 muted / 会话类型，
        // 宁可这一条不提醒也不要猜——已知限制，见任务收尾报告。
        val conv = repo.conversation(owner, m.convId) ?: run {
            log.d("alert_skip_no_conversation", "convId" to m.convId)
            return
        }
        val ctx = AlertContext(
            platform = "mobile",
            isLive = true,
            isSelf = false,
            isSystem = m.contentType == ContentType.SYSTEM,
            isRecalled = (m.recalledAt ?: 0L) > 0L,
            isCallRecord = m.contentType == ContentType.CALL,
            missedCallForMe = m.contentType == ContentType.CALL &&
                CallRecord.isMissedPreview(CallRecord.preview(m.content, viewerIsSender = false)),
            convType = if (conv.isGroup) "group" else "private",
            muted = conv.muted,
            mentionsMe = m.mentionSpans.orEmpty().any { it.uid.isEmpty() || it.uid == owner },
            appActive = AppActive.current,
            windowFocused = true, // 移动端分支不读这个字段
            viewingConv = AppActive.current && ViewingConv.current == m.convId,
            inCall = RtcCall.inCall.value,
            nowMs = System.currentTimeMillis(),
            lastSoundAtMs = AlertPlayer.lastSoundAt,
            settings = NotificationSettingsStore.current,
        )
        val result = AlertDecision.decide(ctx)
        if (result.sound || result.vibrate) AlertPlayer.play(result)
        // P1 应用内横幅（NOTIFICATIONS_P1_DESIGN §1.1/§1.2）：不受节流影响，判定为真就原地换/重新计时，
        // 交给 InAppBannerStore；真正画它、算 4 秒计时与手势的是 ui/components/InAppBanner.kt。
        if (result.banner) {
            val previewOn = ctx.settings.typeOf(group = conv.isGroup).preview
            InAppBannerStore.show(BannerFormat.of(conv, previewOn = previewOn, myUid = owner))
        }
    }
}
