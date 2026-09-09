package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.MsgOpData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.ReceiptData
import com.libeyond.imandroid.sdk.protocol.TypingData
import com.libeyond.imandroid.sdk.protocol.WatchData

/**
 * [MessageService] 的**小帧上行**：typing / watch / msg_op / receipt。
 *
 * 从 MessageService 平移出来（2026-09-09，接 @提及时那个文件触了 600 行红线），**行为未改**。
 * 这四个的共同点是「只发帧，不落库、不等回执」——与需要先落待发行再发帧的
 * [MessageService.sendText] 那一族刚好相反，所以是一条干净的切口。
 *
 * 写成扩展函数而不是搬进另一个类：**调用形态一处不用改**（`service.sendTyping(...)` 照旧），
 * 代价是 `socket`/`log` 从 private 放宽到 internal，以及 `ui` 包的调用点要多一行 import
 *（扩展函数不像成员函数那样自动可见——`MessageWindowQueries` 那次调用点同包所以没这一步）。
 */

/**
 * 上报「正在输入」。上行只带 conv_id，服务端中继时附 from。
 * 节流由调用方负责——每次按键都发是错的。
 */
fun MessageService.sendTyping(convId: String) {
    socket.send(
        FrameType.TYPING,
        ProtocolJson.encodeToJsonElement(TypingData.serializer(), TypingData(convId = convId)),
    )
}

/**
 * 上报当前要显示在线态的 uid 全集（**全量替换语义**）。
 *
 * 服务端对每次 watch（含集合不变的重发）都回快照，故进入界面与重连后都要发一次，
 * 别因为集合没变就跳过——那正是「返回聊天页在线态不刷新」的成因。
 */
fun MessageService.sendWatch(set: Set<String>, force: Boolean = false) {
    if (!presence.updateWatch(set, force)) return
    socket.send(
        FrameType.WATCH,
        ProtocolJson.encodeToJsonElement(
            WatchData.serializer(), WatchData(presence.currentWatchSet()),
        ),
    )
}

/**
 * 发一条消息操作（§6.7）。`client_msg_id` 是幂等键，重发命中不重复应用。
 *
 * **不做本地乐观更新**：撤回/删除有服务端权限与时间窗判定（超窗回 300008、
 * 无权 300006），先在本地删掉再被服务端拒绝，就得把消息变回来——
 * 那比等一下服务端广播回来难看得多。服务端会把 msg_op 广播给**含发起方在内**的
 * 全体成员设备，本端照常从帧里收敛。
 */
fun MessageService.sendMsgOp(
    convId: String,
    op: String,
    targetConvSeq: Long,
    content: String? = null,
    pinned: Boolean? = null,
) {
    socket.send(
        FrameType.MSG_OP,
        ProtocolJson.encodeToJsonElement(
            MsgOpData.serializer(),
            MsgOpData(
                op = op,
                convId = convId,
                targetConvSeq = targetConvSeq,
                clientMsgId = java.util.UUID.randomUUID().toString(),
                content = content,
                pinned = pinned,
            ),
        ),
    )
    log.i("msg_op_sent", "op" to op, "convId" to convId, "target" to targetConvSeq)
}

fun MessageService.sendReceipt(convId: String, status: String, upTo: Long) {
    socket.send(
        FrameType.RECEIPT,
        ProtocolJson.encodeToJsonElement(
            ReceiptData.serializer(),
            ReceiptData(convId = convId, status = status, upToConvSeq = upTo),
        ),
    )
}
