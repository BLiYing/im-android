package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry

/** 「新的朋友 ▸ 已添加」只看最近这么多天（NEW_FRIENDS_DESIGN §0；三端同值）。 */
const val RECENT_ADDED_DAYS = 30

/** 「已添加」段最多这么多条；无「查看更多」，更早的好友去通讯录找。 */
const val RECENT_ADDED_MAX = 50

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * 「已添加」段的数据：`status=accepted` 且 `updatedAt`（毫秒）在 [days] 天内，
 * 按 `updatedAt` 倒序，最多 [max] 条。恰好 30 天整算在内（`>=`）。
 *
 * 靠 `updated_at` 判「刚加的」：服务端只在申请 / 同意时写它，改备注、拉黑不动它。
 */
fun recentAdded(
    friends: List<FriendEntry>,
    nowMs: Long,
    days: Int = RECENT_ADDED_DAYS,
    max: Int = RECENT_ADDED_MAX,
): List<FriendEntry> {
    val cutoff = nowMs - days * DAY_MS
    return friends
        .filter { it.status == FriendEntry.ACCEPTED && it.updatedAt >= cutoff }
        .sortedByDescending { it.updatedAt }
        .take(max)
}
