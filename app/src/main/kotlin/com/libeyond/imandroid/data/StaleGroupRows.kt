package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity

/**
 * 会话列表是**全量权威快照**（`GET /conversations` 无分页），但 `applyConversationList` 只 upsert、不删：
 * 我在别的设备退群（`leave`）/ 离线期间被移出 / 群被解散而这台没收到那一帧时，本机的群行永远留着，
 * 点进去是死页。群行只会由服务端列表（或群帧触发的重拉）写入，所以「本地有、快照里没有」的群行就是陈旧的。
 *
 * **单聊行只判「已有过消息」的**（[isSettled]，`lastConvSeq > 0`）：还没发过消息的新聊天是本地先建的，
 * 服务端那边根本没有，不能按快照删。已有过消息而快照没有 = 别的设备「删除会话 / 清除所有对话」时本机离线，
 * 服务端已把它隐藏（`convListed`），本机该跟着移走（iOS / Web 的快照整体替换也是这个结果）。
 *
 * **只删「发请求之前就已在本机」的行**（[beforeRequest]）：请求在途时新建/新被拉进的群，
 * 服务端那份快照可能早于它——不加这条，刚创建的群会被一份陈旧快照当场删掉（真机实测踩到过）。
 */
object StaleGroupRows {
    /** 请求前取的快照：可判陈旧行 id → 当时的 `lastConvSeq`（单聊靠它判「请求在途时来了新消息」）。 */
    fun snapshot(rows: List<ConversationEntity>): Map<String, Long> =
        rows.filter { isSettled(it) }.associate { it.convId to it.lastConvSeq }

    /** 快照缺失时可判陈旧的行：群，或已有过消息的单聊。 */
    fun isSettled(c: ConversationEntity): Boolean = c.isGroup || c.lastConvSeq > 0

    /**
     * 单聊另须 `lastConvSeq` 与请求前相同：请求在途时来了新消息（快照可能早于它），这一轮不动，下次刷新再判；
     * 否则「清到位点」会把刚到的消息一并清掉。
     */
    fun of(local: List<ConversationEntity>, listedConvIds: Set<String>, beforeRequest: Map<String, Long>): List<ConversationEntity> =
        local.filter { c ->
            val before = beforeRequest[c.convId]
            isSettled(c) && c.convId !in listedConvIds && before != null && (c.isGroup || c.lastConvSeq == before)
        }
}
