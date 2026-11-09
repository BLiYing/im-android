package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ErrorData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 我发起的一条 `msg_op` 被服务端拒了（error 帧带它的 client_msg_id）。 */
data class MsgOpRejection(
    val clientMsgId: String,
    val op: String,
    val convId: String,
    val targetConvSeq: Long,
    val code: Int,
    val message: String,
) {
    /** 给用户看的一句：收录的码走本地化（如 100004 敏感词），否则服务端原文，再否则「操作失败」。 */
    fun userText(): String =
        ErrorText.friendlyRes(code)?.let { Str.s(it) } ?: message.ifBlank { Str.s(R.string.common_action_failed) }
}

/**
 * 发出去还没回音的 `msg_op`（对齐 Web `imSdk.pendingOps`）：error 帧的 client_msg_id 命中这里 = 某个操作被拒，
 * 不是某条 `send_msg` 被拒，**不能**走待发行标失败那条路（待发表里根本没这一行，拒绝就被静默吞掉了）。
 *
 * 成功不回专门的应答（服务端广播 msg_op 本身），所以不按成功摘除，只按条数封顶：最多记 [CAP] 个，
 * 旧的挤出——拒绝都是即时回的，挤出的那些早就有结果了。
 */
class PendingMsgOps {
    private class Op(val op: String, val convId: String, val target: Long)

    private val ops = object : LinkedHashMap<String, Op>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Op>?) = size > CAP
    }
    private val _rejections = MutableSharedFlow<MsgOpRejection>(extraBufferCapacity = 16)
    val rejections: SharedFlow<MsgOpRejection> = _rejections.asSharedFlow()

    fun register(clientMsgId: String, op: String, convId: String, targetConvSeq: Long) {
        synchronized(ops) { ops[clientMsgId] = Op(op, convId, targetConvSeq) }
    }

    /** error 帧命中我发起的操作 → 摘掉并返回拒绝事件；不是操作（是 send_msg 的拒绝）返回 null。 */
    fun take(err: ErrorData): MsgOpRejection? {
        val cid = err.clientMsgId ?: return null
        val o = synchronized(ops) { ops.remove(cid) } ?: return null
        return MsgOpRejection(cid, o.op, o.convId, o.target, err.code, err.message)
    }

    /** [take] + 广播给界面；命中返回 true（调用方不再当作 send_msg 拒绝处理）。 */
    fun reject(err: ErrorData): Boolean {
        val r = take(err) ?: return false
        _rejections.tryEmit(r)
        return true
    }

    companion object {
        const val CAP = 64
    }
}
