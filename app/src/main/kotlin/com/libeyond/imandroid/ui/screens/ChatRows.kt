package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.data.AlbumLayout
import com.libeyond.imandroid.data.ChatEntry
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.db.PendingMessageEntity
import com.libeyond.imandroid.ui.components.TimeFormat

// 从 ChatScreen.kt 拆出（2026-09-07，那份文件到 586/600 行）。
// **拆的是「消息列表长什么样」这件事**：行模型 + 由消息/待发消息推出行序列的纯逻辑，
// 与渲染无关、可单测（AlbumClusterTest / PendingAlbumClusterTest / UnreadDividerTest 都打在这里）。
// 渲染留在 ChatScreen.kt。

/** 聊天页显示的一行：已确认消息 或 待发消息。 */
sealed interface ChatRow {
    /** 身份键——列表 key 一律用它。 */
    val key: String

    data class Confirmed(val msg: MessageEntity) : ChatRow {
        /**
         * **用 convSeq 作身份，绝不用下标**。
         * 入站消息没有 clientMsgId；向上翻页 prepend 后下标整体平移，
         * 用下标会让 Compose 错绑已有节点（播放中的视频、展开的长文跳到别的行）。
         * iOS 与 Web 各踩过一次，见 `../IMServer/docs/SYMMETRY.md`。
         */
        override val key get() = "s${msg.convSeq}"
    }

    data class Pending(val msg: PendingMessageEntity) : ChatRow {
        /** 待发消息还没有 convSeq，用 clientMsgId——它本就是幂等键。 */
        override val key get() = "c${msg.clientMsgId}"
    }

    /**
     * 相册宫格：同 `group_id` 的连续多图/多视频聚成一格（M4+）。
     *
     * **成员仍是各自独立的消息**——撤回/引用/转发/收藏都作用在单条上，
     * 这里只是显示层的聚簇。key 用组内**首条**的 convSeq：组成员集合变化
     * （某张被撤回退出宫格）时 key 会变，正好触发重组。
     */
    data class Album(val msgs: List<MessageEntity>) : ChatRow {
        override val key get() = "a${msgs.first().convSeq}_${msgs.size}"
    }

    /** 待发的一组图。**选完立刻成宫格**（iOS 同），不等 ack。 */
    data class PendingAlbum(val msgs: List<PendingMessageEntity>) : ChatRow {
        override val key get() = "pa${msgs.first().clientMsgId}_${msgs.size}"
    }

    data class DayLabel(val timestamp: Long) : ChatRow {
        override val key get() = "d$timestamp"
    }

    /** 未读分割线（CHAT_UX §3）。 */
    data object UnreadDivider : ChatRow {
        override val key get() = "unread-divider"
    }
}

/** 各行的 conv_seq（非消息行为 0），供 [com.libeyond.imandroid.data.ChatEntry] 定位。 */
fun ChatRow.seqOrZero(): Long = when (this) {
    is ChatRow.Confirmed -> msg.convSeq
    else -> 0L
}

/**
 * 把已确认 + 待发两路合成一条显示流。**纯函数**，便于单测。
 *
 * 排序口径：`timestamp` 主排（三端契约）。待发消息恒在末尾——它们还没有服务端时间戳，
 * 用本地 createdAt，天然就是最新的。
 */
fun buildChatRows(
    confirmed: List<MessageEntity>,
    pending: List<PendingMessageEntity>,
    /** 本人已读位点；插未读分割线用。传 0 且 unread=0 时不插。 */
    readSeq: Long = 0,
    unread: Int = 0,
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var prevTs = 0L
    var dividerPlaced = !ChatEntry.hasUnread(unread)
    val sorted = confirmed.sortedWith(compareBy({ it.timestamp }, { it.convSeq }))
    var i = 0
    while (i < sorted.size) {
        val m = sorted[i]
        if (TimeFormat.needsDaySeparator(prevTs, m.timestamp)) rows += ChatRow.DayLabel(m.timestamp)
        // 分割线插在首条未读**之前**
        if (!dividerPlaced && m.convSeq > readSeq) {
            rows += ChatRow.UnreadDivider
            dividerPlaced = true
        }

        // —— 相册聚簇 ——
        // 只并**相邻**的同组成员：中间隔了别的消息就不是一批发的，硬并会把时间顺序搅乱。
        // 撤回的成员**退出宫格**单独显示墓碑（与 iOS 同）——所以 isAlbumMember 之外还要挡撤回。
        if (AlbumLayout.isAlbumMember(m.contentType, m.groupId) && (m.recalledAt ?: 0) <= 0) {
            var j = i + 1
            while (j < sorted.size &&
                sorted[j].groupId == m.groupId &&
                AlbumLayout.isAlbumMember(sorted[j].contentType, sorted[j].groupId) &&
                (sorted[j].recalledAt ?: 0) <= 0
            ) j++
            val group = sorted.subList(i, j)
            // 只有一张的"相册"就是一张普通图，不要为它画一格宫格
            if (group.size >= 2) {
                rows += ChatRow.Album(group.take(AlbumLayout.MAX))
                prevTs = group.last().timestamp
                i = j
                continue
            }
        }

        rows += ChatRow.Confirmed(m)
        prevTs = m.timestamp
        i++
    }
    val sortedPending = pending.sortedBy { it.createdAt }
    var k = 0
    while (k < sortedPending.size) {
        val p = sortedPending[k]
        if (TimeFormat.needsDaySeparator(prevTs, p.createdAt)) rows += ChatRow.DayLabel(p.createdAt)

        // 待发也聚簇——判据与已确认那路**共用 AlbumLayout.isAlbumMember**，
        // 两边各写一份的话，同一组图在发送中和发送后会长得不一样。
        if (AlbumLayout.isAlbumMember(p.contentType, p.groupId)) {
            var j = k + 1
            while (j < sortedPending.size &&
                sortedPending[j].groupId == p.groupId &&
                AlbumLayout.isAlbumMember(sortedPending[j].contentType, sortedPending[j].groupId)
            ) j++
            val group = sortedPending.subList(k, j)
            if (group.size >= 2) {
                rows += ChatRow.PendingAlbum(group.take(AlbumLayout.MAX))
                prevTs = group.last().createdAt
                k = j
                continue
            }
        }

        rows += ChatRow.Pending(p)
        prevTs = p.createdAt
        k++
    }
    return rows
}
