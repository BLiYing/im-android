package com.libeyond.imandroid.data

/**
 * 实时消息「跳号」判定（OFFLINE_BACKLOG_DESIGN §4.8，C4）。
 *
 * 实时 `new_msg` 的 `conv_seq` 若不是「游标 + 1」，说明中间漏了（断线瞬间、乱序、服务端可见性之外的丢失）。
 * 处理**只发一次 `sync_req`**，由服务端按 `max_gap` 决定补（≤400）还是回 `too_long`——与 §4.5 同一条分水岭，
 * 客户端不自己判深度。
 *
 * **只在实时路径做**：`sync` 路径里的「跳号」是服务端的可见性过滤（`history_visible`、仅为我删除、事件行），
 * 那里自愈只会空转（iOS 同款取舍，`IMSocketManager.m` 的跳号分流）。
 * 对称兄弟：iOS `processIncomingMessage:` 里 `msg.convSeq > prevSynced+1` 的分支。
 */
object GapRule {

    /** [prevSynced] 是这条消息**落库前**的连续游标（会话行不存在按 0）。 */
    fun needsCatchUp(prevSynced: Long, seq: Long): Boolean = seq > 0 && seq > prevSynced + 1

    /** 实时消息恰好接在游标后面：游标跟着前进（不推进的话下次重连会把这些已收到的重拉一遍）。 */
    fun isNextContiguous(prevSynced: Long, seq: Long): Boolean = seq > 0 && seq == prevSynced + 1
}
