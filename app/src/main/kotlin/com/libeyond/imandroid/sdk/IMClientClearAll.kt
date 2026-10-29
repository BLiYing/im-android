package com.libeyond.imandroid.sdk

import com.libeyond.imandroid.data.ClearAllConversations
import com.libeyond.imandroid.data.clearAllToFloors
import com.libeyond.imandroid.fcm.FcmNotifications
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.flow.first

private val log = IMLog.tag("IM.ClearAll")

/** 页面打开时本机会话列表的可见会话数（仅用于确认弹窗正文；toast 以服务端 `cleared` 为准）。 */
suspend fun IMClient.visibleConversationCount(): Int {
    val owner = uid ?: return 0
    return repo.observeConversations(owner).first().size
}

/**
 * 清除所有对话（设计 §4）。请求失败抛出且**本机零变更**；成功后：位点单事务 → `HistoryFloors` 作废 →
 * 媒体缓存（`downloads.clearAll()`，不含 Coil 头像缓存）→ 通知栏本 App 消息通知。会话行随事务移除，
 * 会话 Tab 红点 / 未读由行派生而归零。聊天页正打开时只是消息清空，页面不强退。
 * 回声 `conv_update(delete)` 随后到达，位点只增不减，幂等。
 * @return 服务端 `cleared`
 */
suspend fun IMClient.clearAllConversations(): Int {
    val owner = uid.orEmpty()
    val cleared = ClearAllConversations.run(
        request = { conversationsApi.clearAll() },
        applyLocal = { r ->
            repo.clearAllToFloors(owner, r.items)
            r.items.forEach { messages.historyFloors.remove(it.convId) }
        },
        sideEffects = {
            val freed = downloads.clearAll()
            FcmNotifications.clearAll()
            log.i("clear_all_done", "mediaBytes" to freed)
        },
    )
    // 再拉一次快照：列表阶段的「服务端说有 N 条」是上一次拉取的旧结论，不刷新就一直是「加载中」的空白，出不了空态
    messages.refreshConversations()
    return cleared
}
