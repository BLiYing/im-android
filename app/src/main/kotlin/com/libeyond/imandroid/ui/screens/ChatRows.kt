package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.data.AlbumLayout
import com.libeyond.imandroid.data.ChatEntry
import com.libeyond.imandroid.data.MessageOrder
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
 * 排序口径见 [MessageOrder]（三端共享的不变式）：`timestamp` 主排，
 * 同毫秒时 `conv_seq=0` 视为 +∞ 垫底。待发消息的时间戳取本地 `createdAt`。
 *
 * ⚠️ **两路必须合流后统一排序，不能「已确认一段 + 待发一段」拼起来**。
 * 本函数原先就是拼起来的，注释还写着「待发消息恒在末尾——它们还没有服务端时间戳，
 * 用本地 createdAt，天然就是最新的」。**那个假设对失败的消息不成立**：
 * 16:34 发失败的那条，在 17:11 的消息到达后就不是最新的了，于是它永久钉在最底下，
 * 用户滚到底只看到那条红❗，以为新消息没收到。
 * iOS 2026-08-05 踩过一模一样的坑（`IMDatabase.m` 注释：「从『临时垫底』变成『永久钉底』」）。
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

    // 合流后**一次**排序。相册聚簇仍只并「相邻且同类」的成员：
    // 一组图里已 ack 的那几张和还在发的那几张会挨着，但它们是两种行
    // （Album / PendingAlbum），混并没有意义，也会让「发送中」的暗底失效。
    val items = ArrayList<Item>(confirmed.size + pending.size)
    confirmed.forEach { items += Item.C(it) }
    pending.forEach { items += Item.P(it) }
    items.sortWith { a, b -> MessageOrder.compare(a.ts, a.seq, b.ts, b.seq) }

    var i = 0
    while (i < items.size) {
        val item = items[i]
        if (TimeFormat.needsDaySeparator(prevTs, item.ts)) rows += ChatRow.DayLabel(item.ts)

        when (item) {
            is Item.C -> {
                val m = item.msg
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
                    while (j < items.size) {
                        val n = (items[j] as? Item.C)?.msg ?: break
                        if (n.groupId != m.groupId ||
                            !AlbumLayout.isAlbumMember(n.contentType, n.groupId) ||
                            (n.recalledAt ?: 0) > 0
                        ) break
                        j++
                    }
                    val group = (i until j).map { (items[it] as Item.C).msg }
                    // 只有一张的"相册"就是一张普通图，不要为它画一格宫格
                    if (group.size >= 2) {
                        rows += ChatRow.Album(group.take(AlbumLayout.MAX))
                        prevTs = group.last().timestamp
                        i = j
                        continue
                    }
                }
                rows += ChatRow.Confirmed(m)
            }

            is Item.P -> {
                val p = item.msg
                // 待发也聚簇——判据与已确认那路**共用 AlbumLayout.isAlbumMember**，
                // 两边各写一份的话，同一组图在发送中和发送后会长得不一样。
                if (AlbumLayout.isAlbumMember(p.contentType, p.groupId)) {
                    var j = i + 1
                    while (j < items.size) {
                        val n = (items[j] as? Item.P)?.msg ?: break
                        if (n.groupId != p.groupId ||
                            !AlbumLayout.isAlbumMember(n.contentType, n.groupId)
                        ) break
                        j++
                    }
                    val group = (i until j).map { (items[it] as Item.P).msg }
                    if (group.size >= 2) {
                        rows += ChatRow.PendingAlbum(group.take(AlbumLayout.MAX))
                        prevTs = group.last().createdAt
                        i = j
                        continue
                    }
                }
                rows += ChatRow.Pending(p)
            }
        }
        prevTs = item.ts
        i++
    }
    return rows
}

/**
 * 合流排序用的中性包装：已确认与待发在**排序**这件事上是同一种东西
 * （一个时间戳 + 一个 conv_seq），只是待发的 conv_seq 恒为 0。
 * 不包一层就得写两个循环，而两个循环正是「待发永久钉底」那个 bug 的来源。
 */
private sealed interface Item {
    val ts: Long
    val seq: Long

    @JvmInline
    value class C(val msg: MessageEntity) : Item {
        override val ts get() = msg.timestamp
        override val seq get() = msg.convSeq
    }

    @JvmInline
    value class P(val msg: PendingMessageEntity) : Item {
        override val ts get() = msg.createdAt

        /** 待发消息没有服务端序号——[MessageOrder.seqKey] 会把它当 +∞ 垫底。 */
        override val seq get() = 0L
    }
}
