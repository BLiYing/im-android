package com.libeyond.imandroid.data

// 服务端位点回退的本地对账。写成扩展函数，与 MessageSync.kt / MessageRepositorySend.kt 同一套做法
// （MessageRepository 贴着 600 行硬闸，CODING_STYLE §7②）。

/**
 * 服务端权威 head 比本地记的**小**——本地越过它的那部分是「幽灵」：服务端库被还原 / 压测数据被清之后，
 * 客户端手里仍留着旧库里更大的序号。
 *
 * 不处理的后果（2026-10-04 user1001↔libeyond 实测）：本地 head=998077、服务端真实 130304，
 * 会话落在 130409..998077 一段幽灵行里，真正的最新几条被挤到一大片「缺口」之上，列表预览/未读/↓ 全按错的位点算，
 * 看上去就是「消息没拉全」。head「只增不减」的口径本身没错，这是唯一的例外入口。
 *
 * **为什么只认 `sync_resp.head_conv_seq`、不认会话列表快照**：同一条连接上，本次 `sync_resp` 之前收到的实时帧
 * 序号必然 ≤ 它算 head 那一刻的计数器，所以「本地 head > 它」只可能来自更早的会话（库被还原），
 * 不会是竞态；列表快照是 HTTP，快照发出后到的 new_msg 会让本地合法地比快照新（见 [keepNewerLocalTail]）。
 *
 * @return 是否真的回退了（调用方据此刷新列表预览）
 */
suspend fun MessageRepository.reconcileRegressedHead(owner: String, convId: String, serverHead: Long): Boolean {
    if (serverHead <= 0) return false
    val localHead = conversations.byId(owner, convId)?.headConvSeq ?: return false
    if (localHead <= serverHead) return false
    tx.run {
        messages.deleteAbove(owner, convId, serverHead)
        ranges.truncateAbove(owner, convId, serverHead)
        conversations.lowerHead(owner, convId, serverHead)
    }
    log.w("server_head_regressed", "convId" to convId, "local" to localHead, "server" to serverHead)
    return true
}
