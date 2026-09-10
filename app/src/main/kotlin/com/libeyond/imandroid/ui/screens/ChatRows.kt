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
     * 这里只是显示层的聚簇。
     *
     * **一个宫格里可以同时有「已确认」和「待发」两种格**（[AlbumMember]）。
     * 起初这里是两种行（Album / PendingAlbum），理由写的是「混并没有意义」——
     * 那是错的：一批图**不会同时 ack**，「第 1 张已确认、第 2/3 张还在传」是必经的
     * 中间态，不许混并的结果就是发送全程散成「一张普通图 + 一个两格宫格」，
     * 全部 ack 后才凑回三格。2026-09-08 用户报的「发送完成才变成九宫格」就是这个。
     *
     * key 用 **group_id**：它就是这一组的身份，且**整个生命周期不变**——
     * 用「首格身份 + 格数」当 key 会在每次 ack 时变一次，导致整行被丢弃重建。
     */
    data class Album(val members: List<AlbumMember>) : ChatRow {
        override val key get() = "a${members.first().groupId}"

        /** 已确认的那几条（长按/撤回/引用只对它们成立）。 */
        val sent: List<MessageEntity> get() = members.mapNotNull { (it as? AlbumMember.Sent)?.msg }
    }

    data class DayLabel(val timestamp: Long) : ChatRow {
        override val key get() = "d$timestamp"
    }

    /** 未读分割线（CHAT_UX §3）。 */
    data object UnreadDivider : ChatRow {
        override val key get() = "unread-divider"
    }
}

/**
 * 宫格里的一格：**已确认** 或 **还在发**。
 *
 * 两种状态同处一个宫格是常态而非例外——见 [ChatRow.Album] 的注释。
 * 每格自带 [sending] 标记，压暗底由格子自己表达，不靠"整行是不是待发行"。
 */
sealed interface AlbumMember {
    val groupId: String?
    val sending: Boolean

    @JvmInline
    value class Sent(val msg: MessageEntity) : AlbumMember {
        override val groupId get() = msg.groupId
        override val sending get() = false
    }

    @JvmInline
    value class Sending(val msg: PendingMessageEntity) : AlbumMember {
        override val groupId get() = msg.groupId
        override val sending get() = true
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

    // 合流后**一次**排序。相册聚簇只并「相邻且同组」的成员，但**跨已确认/待发**：
    // 一组图里已 ack 的那几张和还在发的那几张会挨着，它们本来就是同一个宫格，
    // 「发送中」的暗底由**每一格自己**表达（AlbumMember.sending），不靠整行的类型。
    val items = ArrayList<Item>(confirmed.size + pending.size)
    confirmed.forEach { items += Item.C(it) }
    pending.forEach { items += Item.P(it) }
    items.sortWith { a, b -> MessageOrder.compare(a.ts, a.seq, b.ts, b.seq) }

    var i = 0
    while (i < items.size) {
        val item = items[i]
        if (TimeFormat.needsDaySeparator(prevTs, item.ts)) rows += ChatRow.DayLabel(item.ts)

        // 未读分割线插在首条未读**之前**（只有已确认消息有 conv_seq，待发不参与判定）
        if (item is Item.C && !dividerPlaced && item.msg.convSeq > readSeq) {
            rows += ChatRow.UnreadDivider
            dividerPlaced = true
        }

        // —— 相册聚簇 ——
        // 只并**相邻**的同组成员：中间隔了别的消息就不是一批发的，硬并会把时间顺序搅乱。
        // **跨「已确认 / 待发」并**——一批图不会同时 ack，不跨就必然经历"散成单张"的中间态。
        // 撤回的成员**退出宫格**单独显示墓碑（与 iOS 同），所以判据里还要挡撤回。
        if (item.albumGroupId != null) {
            var j = i + 1
            while (j < items.size && items[j].albumGroupId == item.albumGroupId) j++
            if (j - i >= 2) {
                val group = (i until j).map { items[it].asAlbumMember() }
                rows += ChatRow.Album(group.take(AlbumLayout.MAX))
                prevTs = items[j - 1].ts
                i = j
                continue
            }
        }

        rows += when (item) {
            is Item.C -> ChatRow.Confirmed(item.msg)
            is Item.P -> ChatRow.Pending(item.msg)
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

    /**
     * 本条能进宫格时的组 ID，否则 null。
     * **判据两路共用**（[AlbumLayout.isAlbumMember] + 撤回挡板），
     * 各写一份的话同一组图在发送中和发送后会长得不一样。
     */
    val albumGroupId: String?

    fun asAlbumMember(): AlbumMember

    @JvmInline
    value class C(val msg: MessageEntity) : Item {
        override val ts get() = msg.timestamp
        override val seq get() = msg.convSeq
        override val albumGroupId get() =
            msg.groupId.takeIf {
                AlbumLayout.isAlbumMember(msg.contentType, it) && (msg.recalledAt ?: 0) <= 0
            }
        override fun asAlbumMember(): AlbumMember = AlbumMember.Sent(msg)
    }

    @JvmInline
    value class P(val msg: PendingMessageEntity) : Item {
        override val ts get() = msg.createdAt

        /** 待发消息没有服务端序号——[MessageOrder.seqKey] 会把它当 +∞ 垫底。 */
        override val seq get() = 0L
        override val albumGroupId get() =
            msg.groupId.takeIf { AlbumLayout.isAlbumMember(msg.contentType, it) }
        override fun asAlbumMember(): AlbumMember = AlbumMember.Sending(msg)
    }
}

// —— 以下从 ChatScreen.kt 平移（2026-09-10，那份文件到 595/600 行）：按 seq 反查行的纯函数 ——

/**
 * 一条消息的引用快照该显示什么。
 *
 * 三档，优先级从高到低：
 * ① 服务端**发送时冻结**的 `reply_snapshot`（原消息后续被删/撤回仍可展示）；
 * ② 本地那条原消息**现算**——`ack` 只回 5 个字段，冻结快照回不来，
 *    所以自己发的引用消息在自己这一侧只有这一档可用；
 * ③ 都没有 → 「原消息」（同 iOS `IMBubbleCell` 的兜底文案）。
 *
 * `replyToConvSeq <= 0` 表示这条不是引用，返回 null 让调用方整块不画。
 */
/**
 * 被引用消息在**本地**的那一条（宫格成员也算）。找不到 = 翻不到那么早 / 已被删。
 *
 * 引用块要的两样东西都从它来：**真缩略图**（快照是冻结的文字，不带 thumb）
 * 与**跳转目标**。所以这两件事天然是同一块——iOS 也是先反查再决定画什么/能不能点。
 */
internal fun originalOf(rows: List<ChatRow>, seq: Long): MessageEntity? {
    if (seq <= 0) return null
    for (r in rows) {
        when (r) {
            is ChatRow.Confirmed -> if (r.msg.convSeq == seq) return r.msg
            is ChatRow.Album -> r.sent.firstOrNull { it.convSeq == seq }?.let { return it }
            else -> Unit
        }
    }
    return null
}

/** 这一行在列表里的下标（跳转要用）。宫格里的某一格算它所在的那一行。 */
internal fun rowIndexOfSeq(rows: List<ChatRow>, seq: Long): Int {
    if (seq <= 0) return -1
    return rows.indexOfFirst { r ->
        when (r) {
            is ChatRow.Confirmed -> r.msg.convSeq == seq
            is ChatRow.Album -> r.sent.any { it.convSeq == seq }
            else -> false
        }
    }
}

internal fun quoteSnapshotFor(rows: List<ChatRow>, msg: MessageEntity): String? {
    val seq = msg.replyToConvSeq ?: return null
    if (seq <= 0) return null
    msg.replySnapshot?.takeIf { it.isNotBlank() }?.let { return it }
    val original = originalOf(rows, seq) ?: return "原消息"
    return replyPreviewOf(original.contentType, original.content, original.fileName, original.caption)
}
