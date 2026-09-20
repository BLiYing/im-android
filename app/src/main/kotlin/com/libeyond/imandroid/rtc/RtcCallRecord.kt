package com.libeyond.imandroid.rtc

import com.imrtc.engine.IMCallSummary
import com.libeyond.imandroid.data.CallRecord

/**
 * 一通电话结束后要不要往 IM 里落一条通话记录、落什么（设计 `CALL_RECORD_DESIGN.md` §2）。
 *
 * **纯函数**：SDK 的 [IMCallSummary] 已经给齐了事实（谁是主叫、结局、服务端时长），宿主不再记任何通话上下文，
 * 也**不判忙**——只按 `role` 过滤。真正发消息在 [RtcCall.onCallRecord] 的接收方（要 IM 客户端）。
 */
data class CallRecordPlan(
    val callId: String,
    /** 单聊：对端 uid（消息发到「我—对端」单聊）。群通话为空。 */
    val peerUid: String,
    /** 群通话：宿主的群会话 id（`g_…`）。单聊为空。 */
    val chatGroupId: String,
    /** 消息体 `{"cid","m","r","d"[,"g":1]}`。 */
    val json: String,
) {
    val isGroup: Boolean get() = chatGroupId.isNotEmpty()
}

object RtcCallRecords {

    /** `null` = 不发：被叫、无 call_id、缺目标会话、或 `*_elsewhere`（主叫永远收不到，防御一下）。 */
    fun planFor(s: IMCallSummary): CallRecordPlan? {
        if (s.role != "caller" || s.callId.isEmpty()) return null
        val reason = s.reason.wire
        if (reason.endsWith("_elsewhere")) return null
        val video = s.mediaType == "video"
        val json = CallRecord.encode(s.callId, video, reason, s.durationSec.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), s.isGroup)
        return if (s.isGroup) {
            if (s.chatGroupId.isEmpty()) null else CallRecordPlan(s.callId, "", s.chatGroupId, json)
        } else {
            if (s.peer.isEmpty()) null else CallRecordPlan(s.callId, s.peer, "", json)
        }
    }
}
