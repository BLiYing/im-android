package com.libeyond.imandroid.data

/**
 * 会话设置 PUT 要带的 `pinned_at`（PROTOCOL §6.10 整体替换）。
 *
 * 置顶时间**只在「由关变开」时取现在**；已置顶时改免打扰 / 标未读要把原值原样带回，
 * 否则置顶会话之间的顺序会被打乱。聊天信息页（`ChatDetailHost`）与群资料页
 * （`GroupInfoSettingsState`）共用这一条，改规则只改这里。
 */
object PinnedAt {
    fun next(newPinned: Boolean, currentPinnedAt: Long, nowMs: Long = System.currentTimeMillis()): Long = when {
        !newPinned -> 0L
        currentPinnedAt > 0 -> currentPinnedAt
        else -> nowMs
    }
}
