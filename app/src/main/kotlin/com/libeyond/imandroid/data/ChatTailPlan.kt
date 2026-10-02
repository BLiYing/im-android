package com.libeyond.imandroid.data

/**
 * 「最新一页齐不齐 / 要不要去服务端取最新一页」的判据（OFFLINE_BACKLOG_DESIGN §4.8，C4）。纯函数。
 *
 * 对称兄弟：iOS `IMProgram/Common/IMChatWindowPlan.h`（`IMChatLatestPageLow` / `IMChatTailTip` / `IMChatShouldRequestTail` /
 * `IMChatBumpShouldCatchUp`）、Web `src/windowPlan.ts`（`planJumpToLatest` / `planBumpCatchUp`）。**对称的是不变式**：
 *
 * 1. **判据是区间覆盖，不是「本地最大 seq == head」**：跳号的实时消息登记成孤岛后，本地最大 seq 已经等于 head，
 *    但中间是缺口；占号不成消息的行（事件行/墓碑）也让「最大 seq」撒谎。
 * 2. **最新页的下沿是 `tip - page + 1`，不是 `tip - page`**：`window_req(anchor=0, before=page)` 拿回的是**以 tip 结尾的 page 条**
 *    （`[tip-page+1, tip]`）。多算一格会让刚取回的一页永远判不齐，每次点 ↓ 白问一次服务端。
 * 3. **tip 未知**（`≤0`，重登后内存/库里都没有）：空窗必须问（否则进本地空会话永久空白，13 万条积压实测踩过），
 *    有内容就不白跑。
 * 4. **可见范围内一条没有**（本机清空位点、入群前不可见）：不问。
 */
object ChatTailPlan {

    /** 最新一页的下沿（含）。 */
    fun latestPageLow(tip: Long, page: Int): Long = maxOf(1L, tip - page + 1)

    /** 在有效下界之上取最新页的下沿：`visibleFrom` 之下的永远不要。 */
    fun latestPageLowAboveFloor(tip: Long, page: Int, visibleFrom: Long): Long =
        maxOf(latestPageLow(tip, page), visibleFrom)

    /** 会话最新位点：服务端记过的 head 与列表里的最新位点取大；都没有 0。 */
    fun tip(head: Long, lastConvSeq: Long): Long = maxOf(head, lastConvSeq, 0L)

    /**
     * 有效可见起点（含，第一条该显示的 `conv_seq`）：本机清空位点之后。0 = 不设。
     * 与 iOS `IMChatEffectiveFloor` 同为「包含口径」（`cleared + 1`）。服务端可见下界（C3）将来在这里取大。
     */
    fun visibleFrom(clearedUpTo: Long): Long = if (clearedUpTo > 0) clearedUpTo + 1 else 0L

    /** 有效可见起点 = max(本机清空位点之后, 服务端可见下界)。两个下界**各自独立存、用时才取大**。 */
    fun visibleFrom(clearedUpTo: Long, historyFloor: Long): Long = maxOf(visibleFrom(clearedUpTo), maxOf(0L, historyFloor))

    /**
     * `has_before=false` 时可见下界该记在哪：**本窗下沿里客户端真正留下的最小 seq**（含）；一条没留下就退回锚点。
     * 必须记位点不能记布尔——`has_before` 是相对**本窗下沿**说的，不是相对整条会话（从搜索结果跳进旧岛、上滑到岛顶时同样为 false）。
     * 喂进来的 [minKeptSeq] 只能算**渲染得出来的行**（不含 msg_op 事件行与墓碑），否则闸钉在渲染不出的号上永不收敛。
     */
    fun floorFromWindow(minKeptSeq: Long, anchor: Long): Long = if (minKeptSeq > 0) minKeptSeq else maxOf(0L, anchor)

    /** 只往小里收：下界会变小（群主关「仅可见入群后」时服务端返回更低的位点），不能被更大的旧值钉死。0 = 未知，不参与。 */
    fun mergeHistoryFloor(current: Long, incoming: Long): Long = when {
        incoming <= 0 -> maxOf(0L, current)
        current <= 0 -> incoming
        else -> minOf(current, incoming)
    }

    /** 渲染窗口最上面那条之上还有没有（本地或服务端）可看的：到了可见起点就没有了。 */
    fun hasMoreAbove(oldestRendered: Long, visibleFrom: Long): Boolean {
        if (oldestRendered <= 0) return false // 窗口里全是待发消息：没有可作边界的位点
        return oldestRendered > maxOf(1L, visibleFrom)
    }

    /**
     * 要不要去服务端取最新一页。
     * @param covered 最新页 `[latestPageLowAboveFloor, tip]` 是否被**同一段**区间覆盖
     * @param windowTailHi 当前窗口里最新一条的 `conv_seq`（空窗 0）
     */
    fun shouldRequestTail(tip: Long, covered: Boolean, windowTailHi: Long, visibleFrom: Long): Boolean {
        if (tip <= 0) return windowTailHi <= 0
        if (visibleFrom > 0 && tip < visibleFrom) return false
        return !covered
    }

    /**
     * 超级群 / 慢路径 `conv_bump` 到了，要不要补最新一页：**只有用户正贴着底（在跟）才补**——
     * 翻历史时补会把人拽走，那时只让 ↓N 按 head 计数。窗口已含最新（信号晚到）也不补。
     */
    fun bumpShouldCatchUp(following: Boolean, head: Long, tailHi: Long): Boolean =
        following && head > 0 && head > tailHi
}
