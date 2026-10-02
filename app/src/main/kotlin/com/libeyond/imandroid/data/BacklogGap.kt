package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.SyncCursorItem
import com.libeyond.imandroid.sdk.protocol.SyncDefaults

/**
 * `sync_req` 游标的**积压深度预算**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.4 / §4.5）。
 *
 * 判定在服务端（`head − since > max_gap` → 不查消息表、回 `too_long`），客户端只报「我最多接受多大的差距」：
 * - 普通会话 [SyncDefaults.MAX_GAP]（400 = 2 页）：差得少顺手补上，差得多留缺口；
 * - **超级群 0**：永远 `too_long`、永不自动补拉（SUPERGROUP_DESIGN §5「打开会话才拉正文」）。
 *
 * 超级群标记 [com.libeyond.imandroid.data.db.ConversationEntity.isSuper] **只由会话列表快照写**（iOS 踩过：
 * 别处顺手传一个「非超级群」就把标记清掉，该群 `max_gap` 退回 400，自动补拉复发）。
 * 对称兄弟：iOS `IMBacklogTracker maxGapForConv:`、Web `imSdk` 的 `superConvs`。
 */
object BacklogGap {

    fun maxGap(isSuper: Boolean): Long = if (isSuper) 0L else SyncDefaults.MAX_GAP

    fun cursorOf(convId: String, since: Long, isSuper: Boolean): SyncCursorItem =
        SyncCursorItem(convId, since, maxGap = maxGap(isSuper))
}
