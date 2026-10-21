package com.libeyond.imandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.BatchResult
import com.libeyond.imandroid.data.ConversationSelection
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.runBounded
import com.libeyond.imandroid.sdk.IMClient

// 会话列表编辑模式（批量已读 / 删除）的状态与批量执行。设计 IMServer/docs/design/CONV_LIST_EDIT_MODE_DESIGN.md。
// 从 ChatsHost 拆出：ChatsHost 只持一个 [ConversationEditState] + 调这里的两个批量函数。

private val log = com.libeyond.imandroid.sdk.logging.IMLog.tag("IM.ConvEdit")

/** 批量请求同时在途的上限（设计 §4）。 */
internal const val CONV_BATCH_CONCURRENCY = 4

/** 编辑模式状态：是否在编辑、选择集、批量执行中（防重入）。 */
internal class ConversationEditState {
    var editing by mutableStateOf(false)
    var selection by mutableStateOf(ConversationSelection())
    var busy by mutableStateOf(false)
    var confirmingDelete by mutableStateOf(false)

    fun enter() {
        editing = true
        selection = ConversationSelection()
    }

    fun exit() {
        editing = false
        selection = ConversationSelection()
        confirmingDelete = false
    }
}

/**
 * 批量已读：与单条「设为已读」同一路径——未读数 → 推进已读位点（`markRead` 到会话最新位点）；
 * 手动标未读 → 设置接口清标记（`muteUntil` 缺省，不碰免打扰）。无未读的选中项由调用方先筛掉。
 * 结束后**只重拉一次**会话列表。
 */
internal suspend fun batchMarkRead(client: IMClient, items: List<ConversationEntity>): BatchResult<ConversationEntity> {
    val result = runBounded(items, CONV_BATCH_CONCURRENCY) { c ->
        if (c.unread > 0) client.messages.markRead(c.convId, maxOf(c.headConvSeq, c.lastConvSeq))
        if (c.markedUnread) settings(client, c, markedUnread = false)
    }
    client.messages.refreshConversations()
    if (result.failed.isNotEmpty()) log.w("conv_batch_mark_read_failed", "failed" to result.failed.size, "total" to items.size)
    return result
}

/** 批量删除（仅为我删除，同单条）：每条不单独拉列表，结束后只拉一次。 */
internal suspend fun batchDelete(client: IMClient, items: List<ConversationEntity>): BatchResult<ConversationEntity> {
    val result = runBounded(items, CONV_BATCH_CONCURRENCY) { c -> client.messages.deleteConversation(c.convId, refresh = false) }
    client.messages.refreshConversations()
    if (result.failed.isNotEmpty()) log.w("conv_batch_delete_failed", "failed" to result.failed.size, "total" to items.size)
    return result
}
