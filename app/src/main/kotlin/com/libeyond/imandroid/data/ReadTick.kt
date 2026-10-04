package com.libeyond.imandroid.data

/**
 * 「我发的消息」的已读勾（对齐 iOS `peerReadSeq` 复用）：**单聊与群聊共用同一个字段**——
 * 单聊 = 对端读位点（实时靠 receipt 帧）；群聊 = `group_read_seq`（= 其他成员读位点的最小值，**不实时**：
 * 服务端刻意不把群回执扇出给其他成员，只在会话列表快照 / 同步时回带）。
 * 群聊双勾的含义是「所有人都读过」。
 */
object ReadTick {
    /** 不画任何勾（超级群：服务端连 `group_read_seq` 都不算，画 ✓ 也是在撒谎）。 */
    const val HIDDEN = -1L

    /** 聊天页给气泡的读位点：超级群隐藏，其余取本会话行的 `peerReadSeq`。 */
    fun seqFor(isGroup: Boolean, isSuper: Boolean, peerReadSeq: Long): Long =
        if (isGroup && isSuper) HIDDEN else peerReadSeq

    /**
     * 会话快照落库时这一列写什么。群聊取 `group_read_seq`，且**只增不减**（读位点单调，
     * 一份过期的快照不能把已经变绿的勾退回去）；单聊照旧直接取服务端值。
     */
    fun seed(isGroup: Boolean, existing: Long, peerReadSeq: Long, groupReadSeq: Long): Long =
        if (isGroup) maxOf(existing, groupReadSeq) else peerReadSeq

    /** 这条我发的消息要不要画已读双勾（蓝）。`0` = 还没人读过；[HIDDEN] 一律不画。 */
    fun isRead(readSeq: Long, convSeq: Long): Boolean = readSeq > 0 && readSeq >= convSeq
}
