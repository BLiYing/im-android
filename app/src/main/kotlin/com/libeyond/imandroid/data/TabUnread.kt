package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity

/**
 * 底部「消息」Tab 上那颗蓝点的计数（>0 即亮）。
 *
 * **三端同一口径**：im-web `src/desktopNotify.ts` 的 `badgeCountOf`、iOS `IMProgram/Common/IMUnreadBadge.h`
 * 的 `IMTabUnreadCount`（SYMMETRY 已登记）。
 * - 免打扰的会话**不计**——为了消掉点去点开一个明确说过别打扰的会话，就是免打扰没生效。
 * - 但免打扰里 **@我 仍计 1**：@ 穿透免打扰是三端的会话行口径，Tab 上不能反过来把它藏掉。
 * - 手动「标为未读」不计：它是给那一行的记号，不是新消息。
 *
 * 此前本端是一条 `SUM(unread) WHERE muted = 0` 的 SQL，漏了免打扰里被 @ 的那一条，且 JVM 单测钉不住。
 */
object TabUnread {
    fun count(conversations: List<ConversationEntity>): Int = conversations.sumOf { c ->
        when {
            !c.muted -> c.unread
            c.mentionUnread -> 1
            else -> 0
        }
    }
}
