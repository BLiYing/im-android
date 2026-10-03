package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity

/**
 * 会话列表是**全量权威快照**（`GET /conversations` 无分页），但 `applyConversationList` 只 upsert、不删：
 * 我在别的设备退群（`leave`）/ 离线期间被移出 / 群被解散而这台没收到那一帧时，本机的群行永远留着，
 * 点进去是死页。群行只会由服务端列表（或群帧触发的重拉）写入，所以「本地有、快照里没有」的群行就是陈旧的。
 *
 * 只判**群**：单聊行有本地先建的可能（还没发过消息的新聊天），不能按快照删。
 *
 * **只删「发请求之前就已在本机」的群行**（[beforeRequest]）：请求在途时新建/新被拉进的群，
 * 服务端那份快照可能早于它——不加这条，刚创建的群会被一份陈旧快照当场删掉（真机实测踩到过）。
 */
object StaleGroupRows {
    fun of(local: List<ConversationEntity>, listedConvIds: Set<String>, beforeRequest: Set<String>): List<String> =
        local.filter { it.isGroup && it.convId !in listedConvIds && it.convId in beforeRequest }.map { it.convId }
}
