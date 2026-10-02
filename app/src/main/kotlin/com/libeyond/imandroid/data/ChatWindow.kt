package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 聊天页的**渲染窗口**（`../IMServer/docs/design/MESSAGE_WINDOW_DESIGN.md` §4）。
 *
 * 三端共用同一套心智模型：窗口是**单一连续区间**，跳转即换锚点重开，不并存多段
 *（否则内存里会攒起互不相连的几段，既费内存又让"向下滚"的语义变复杂）。
 * 对端是 iOS `IMChatViewController+Window.m` 与 im-web `src/renderWindow.ts`。
 *
 * 本端只有两态：
 * - [Tail]：贴着最新的一窗。新消息自然进来，「回到最新」回到的就是它。
 * - [Anchored]：钉在某条消息附近的一窗（点引用块/搜索命中/归档定位跳过去时用）。
 *   **新消息不会进这一窗**——这是对的：用户正在看历史。想回最新走 ↓ 按钮。
 *
 * ### 为什么不是「最近 N 条 + 把 N 撑大」
 * 那是 2026-09-09 上午的做法，跳到很早的一条要把 N 撑到覆盖它，于是必须有上限
 *（13 万条整窗构造对象会把聊天页渲染成空白），上限之外只能如实说跳不过去。
 * 锚点窗没有这个问题：不论目标多早，取的都是它前后各一页。
 */
sealed interface ChatWindow {

    /**
     * 贴着最新的一窗（进会话、回到最新）。
     *
     * [fromSeq] 是**下界**（含，`conv_seq`）：0 = 不设。本地有缺口（区间清单里有多段）时尾窗必须只取**最新那一段**，
     * 否则「最近 N 条」会把缺口另一侧的旧岛拼到窗口里——用户看到的是跨了缺口的假连续。
     * 值取最新那一段的 `lo`（[MessageRepository.tailState]）。单段完整会话它就是 1，与不设等价。
     */
    data class Tail(val limit: Int, val fromSeq: Long = 0) : ChatWindow

    /**
     * 钉在某段区间上的一窗，边界用**显示序坐标**（timestamp 主排、同毫秒按 conv_seq）。
     *
     * 边界必须与 `MessageDao` 的 `ORDER BY timestamp, convSeq` 逐字对应——
     * 用纯 conv_seq 表达区间会在"同毫秒乱序"那一小段上取错行。
     */
    data class Anchored(
        val loTs: Long,
        val loSeq: Long,
        val hiTs: Long,
        val hiSeq: Long,
    ) : ChatWindow

    val isTail: Boolean get() = this is Tail
}

/** 显示序上的一个坐标（timestamp 主排，同毫秒按 conv_seq）。 */
data class SeqPoint(val timestamp: Long, val convSeq: Long)

object ChatWindows {

    /** 进会话与「回到最新」的尾窗条数。 */
    const val TAIL_LIMIT = 200

    /** 进会话有未读时锚点之前带多少条已读上下文（让未读分割线不贴屏幕顶）。 */
    const val ENTRY_BEFORE = 25

    /** 进会话有未读时锚点之后取多少条。 */
    const val ENTRY_AFTER = 100

    /** 向服务端要「最新一页」时取多少条（`window_req(anchor=0, before=LATEST_FETCH)`）。 */
    const val LATEST_FETCH = 100

    /** 尾窗向上翻一页加多少条。 */
    const val TAIL_PAGE = 200

    /** 跳到某条时，它前后各取多少条（同 MESSAGE_WINDOW_DESIGN §4 的 before/after=50）。 */
    const val ANCHOR_HALF = 100

    /** 锚点窗向上翻一页把下界再往前挪多少条。 */
    const val ANCHOR_PAGE = 100

    /**
     * 由「锚点前后各取到的那几行」算出窗口边界。
     *
     * @param before 锚点**之前**的行，按显示序倒序（近→远）。可能不足 [ANCHOR_HALF]（会话开头）。
     * @param atOrAfter 锚点**及其之后**的行，按显示序正序。第一行就是锚点本身（若它可见）。
     *
     * 两侧都空 = 这个会话在本地一条都没有 → 回 null，调用方据此走"本地没有"那条路。
     * **一侧空是正常的**（锚点就是最早/最新那条），此时那一侧的边界取锚点自己。
     */
    fun boundsOf(before: List<SeqPoint>, atOrAfter: List<SeqPoint>): ChatWindow.Anchored? {
        val lo = before.lastOrNull() ?: atOrAfter.firstOrNull() ?: return null
        val hi = atOrAfter.lastOrNull() ?: before.firstOrNull() ?: return null
        return ChatWindow.Anchored(lo.timestamp, lo.convSeq, hi.timestamp, hi.convSeq)
    }

    /** 换完窗后等多久还没滚到，就认输并如实说一句。 */
    const val LOCATE_TIMEOUT_MS = 3_000L

    /**
     * 目标不在本机、但本地**确有缺口**——它多半只是还没同步下来。
     * 同 im-web `NEED_NETWORK_NOTICE`（三端同一处境同一句话）。
     */
    val NEED_NETWORK_NOTICE: String get() = Str.s(R.string.conv_query_need_network)

    /**
     * 目标真的没了。两个来源：本地齐全却找不到（撤回 / 为所有人删除 / 仅为我删除都是物理删行），
     * 或服务端 `window_resp` 回了 `anchor_found=false`——**后者才是权威的那一份**，
     * 这正是 `window_req` 要区分的两件事（MESSAGE_WINDOW_DESIGN §3.2）。
     *
     * 与 [NEED_NETWORK_NOTICE] 必须分开：引用块自 2026-09-09 起**只要有原消息号就可点**，
     * "自己删掉的消息"是一条常见路径，对它说"需要联网加载"是把原因归错了地方。
     */
    val GONE_NOTICE: String get() = Str.s(R.string.chat_window_gone_notice)

    /**
     * 换完窗、等满 [LOCATE_TIMEOUT_MS] 仍没滚过去。**不说"原消息不在了"**——它明明在，只是没跳成。
     *
     * 兜底而不是主路径：正常情况下换完窗下一帧就命中了。但只要有任何一条路让目标进不了列表，
     * 没有这道兜底就是**唯一一个不给任何反馈的失败分支**——而这一族的纪律是"跳不了要说出来"。
     */
    val LOCATE_FAILED_NOTICE: String get() = Str.s(R.string.chat_window_locate_failed_notice)

    /** 「跳到最早」时本地一条消息都没有（空会话）。对齐 iOS `chat.search.no_messages`。 */
    val NO_MESSAGES_NOTICE: String get() = Str.s(R.string.chat_search_no_messages)

    /**
     * 「跳到最早」离线时的退化：只能落到本地已经握着的那一条，说清楚这不是会话开头。
     * 对齐 iOS `chat.search.offline_jumped_earliest`。
     */
    val OFFLINE_JUMPED_EARLIEST_NOTICE: String get() = Str.s(R.string.chat_search_offline_jumped_earliest)

    /**
     * 「回到最新」按钮该不该亮。
     *
     * 两个理由各自独立：
     * ① **窗口停在历史**——这条最容易漏。跳转不产生滚动事件，而且跳过去的那一段常常整屏放得下，
     *    连"离底很远"的兜底都轮不到；不判这一条，用户跳完就回不去了
     *    （`CLIENT_PARITY.md` 那条「跨窗口定位一次到位 + 有回程」的第 ③ 点，Web 与 iOS 都栽过）。
     * ② 在尾窗里但离底很远（既有判据 [ChatEntry.NEAR_BOTTOM_SLACK]）。
     */
    fun showsJumpToLatest(window: ChatWindow, awayFromBottom: Boolean): Boolean =
        window !is ChatWindow.Tail || awayFromBottom
}
