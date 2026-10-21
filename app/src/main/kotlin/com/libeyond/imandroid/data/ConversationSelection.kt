package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 会话列表「编辑模式」的选择集（不可变；设计 `IMServer/docs/design/CONV_LIST_EDIT_MODE_DESIGN.md` §2）。
 * 按**会话 ID** 记录，切文件夹 Tab 时由调用方换成空集；全选只管当前列表。
 * 三端同口径：iOS `IMConversationSelection`、Web `convSelection.ts`（共用向量 `docs/conformance/conv_selection.json`）。
 */
data class ConversationSelection(val ids: Set<String> = emptySet()) {
    val count: Int get() = ids.size

    fun isSelected(convId: String): Boolean = convId.isNotEmpty() && convId in ids

    /** 勾选 / 取消。 */
    fun toggle(convId: String): ConversationSelection =
        if (convId.isEmpty()) this else if (convId in ids) copy(ids = ids - convId) else copy(ids = ids + convId)

    fun deselect(convIds: Collection<String>): ConversationSelection = copy(ids = ids - convIds.toSet())

    /** 选中 [list] 内全部会话（保留 list 之外已选的项）。 */
    fun selectAll(list: List<ConversationEntity>): ConversationSelection = copy(ids = ids + list.map { it.convId })

    /** [list] 非空且其中每一项都已选中。 */
    fun isAllSelectedIn(list: List<ConversationEntity>): Boolean = list.isNotEmpty() && list.all { it.convId in ids }

    /** 只保留仍存在于 [list] 的 ID（他端删除 / 退群 / 切范围后剔除）。 */
    fun pruneTo(list: List<ConversationEntity>): ConversationSelection {
        val alive = list.mapTo(HashSet()) { it.convId }
        val kept = ids.filterTo(HashSet()) { it in alive }
        return if (kept.size == ids.size) this else copy(ids = kept)
    }

    /** 选中项里至少有一项有未读（「已读」按钮可用）。 */
    fun hasUnreadIn(list: List<ConversationEntity>): Boolean = list.any { it.convId in ids && hasUnread(it) }

    /** 按 [list] 顺序取出已选会话。 */
    fun selectedIn(list: List<ConversationEntity>): List<ConversationEntity> = list.filter { it.convId in ids }

    companion object {
        /** 「有未读」：未读数 > 0 或手动标为未读——与单条菜单「设为已读」的出现条件同口径（[ConversationActions]）。 */
        fun hasUnread(c: ConversationEntity): Boolean = c.unread > 0 || c.markedUnread
    }
}

/** 批量执行结果：按 items 原顺序分成功 / 失败两组。 */
data class BatchResult<T>(val succeeded: List<T>, val failed: List<T>)

/**
 * 有界并发的批量执行器：同时在途最多 [limit] 个，全部结束后一次性返回。
 * 会话列表批量已读 / 删除用它压住逐条接口的瞬时请求量（设计 §4）。[work] 抛异常即该项失败（取消异常除外，照常向上抛）。
 */
suspend fun <T> runBounded(items: List<T>, limit: Int, work: suspend (T) -> Unit): BatchResult<T> {
    if (items.isEmpty()) return BatchResult(emptyList(), emptyList())
    val gate = Semaphore(limit.coerceAtLeast(1))
    val ok = coroutineScope {
        items.map { item ->
            async {
                gate.withPermit {
                    try {
                        work(item)
                        true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        }.map { it.await() }
    }
    return BatchResult(items.filterIndexed { i, _ -> ok[i] }, items.filterIndexed { i, _ -> !ok[i] })
}
