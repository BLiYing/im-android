package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.GroupReadData

// 群「全员已读」实时帧的落库。写成扩展函数，与 MessageSync.kt / MessageRepositorySend.kt 同一套做法
// （MessageRepository 贴着 600 行硬闸，CODING_STYLE §7②）。

/**
 * `group_read`：本人视角的「除我之外全员已读到哪」变大了。写进会话行 `peerReadSeq`（群聊这一列就是它，
 * 见 [ReadTick]），只增不减。聊天页订阅着这一列（`observePeerReadSeq`），会话列表观察会话表，
 * 写库即刷新——发送方「最后一个人读完」1–2 s 内单勾变双勾。
 * 设计见 IMServer `docs/design/GROUP_READ_REALTIME_DESIGN.md`。
 */
suspend fun MessageRepository.applyGroupRead(owner: String, d: GroupReadData) {
    if (d.convId.isBlank() || d.groupReadSeq <= 0) return
    conversations.raiseGroupRead(owner, d.convId, d.groupReadSeq)
}
