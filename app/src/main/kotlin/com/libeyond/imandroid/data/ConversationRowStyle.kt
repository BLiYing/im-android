package com.libeyond.imandroid.data

/**
 * 会话列表行的未读徽标口径（逐字对齐 iOS `IMConversationCell` / `IMUnreadBadge.m`，纯逻辑便于单测）。
 *
 * - 徽标 / 标未读圆点的底色：**免打扰且没被 @我 → 灰；否则蓝**（@ 穿透免打扰）。
 * - 数字：真实未读数的紧凑写法（1.2K / 1.2M），不再一律「99+」。
 */
object ConversationRowStyle {

    /** true = 用 `unreadBadge`（蓝）；false = 用 `unreadBadgeMuted`（灰）。 */
    fun strongAlert(mutedNow: Boolean, mentionUnread: Boolean): Boolean = !mutedNow || mentionUnread

    /** iOS `IMCompactCount`：<1000 原样；≥1000 为 `1.2K`（余 0 则 `1K`）；≥100 万为 `1.2M`。 */
    fun compactCount(n: Int): String = when {
        n <= 0 -> "0"
        n >= 1_000_000 -> compact(n / 1_000_000, (n % 1_000_000) / 100_000, "M")
        n >= 1_000 -> compact(n / 1_000, (n % 1_000) / 100, "K")
        else -> n.toString()
    }

    private fun compact(whole: Int, tenth: Int, unit: String) = if (tenth != 0) "$whole.$tenth$unit" else "$whole$unit"
}
