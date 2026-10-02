package com.libeyond.imandroid.data

/** 进会话取哪一窗：本地齐全就开本地，否则向服务端要**这一屏**。 */
sealed interface EntryPlan {
    /** 本地目录盖得住这一窗，一个请求都不发。 */
    data object Local : EntryPlan

    /** 发 `window_req(anchor, before, after)`。`anchor=0` 是「取最新」哨兵。 */
    data class Server(val anchor: Long, val before: Int, val after: Int) : EntryPlan
}

/**
 * 进会话的取数分流（OFFLINE_BACKLOG_DESIGN §4.6，C3）。纯函数。
 *
 * 对称兄弟：im-web `src/windowPlan.ts` 的 `entryWindowAnchor` / `planEntryWindow`、iOS `IMChatViewController+Window.m` 的
 * `loadInitialWindow` + `IMChatWindowPlan.h` 的 `IMChatEntryHasUnread` / `IMChatEntryWindowAnchor`。**对称的是不变式**：
 *
 * 1. **「有没有未读」只认服务端真实未读数**，不用 `latest > read` 顶替（发送方的读位点天然落后于自己刚发的一堆，
 *    2026-09-03 实测：灌 1 万条后本人进会话被锚到一万条之前）。
 * 2. **有未读时锚点最小是 1，不是 0**：`window_req` 里 `anchor<=0` 是「取最新」哨兵，与 `sync_req` 的 `since=0`（从头）语义相反；
 *    写成 0 则 `readSeq=0` 的新成员拿回最新一页，「可见即读」把读位点推到头——十万未读进一次会话清零（2026-09-03 实测）。
 * 3. **取最新的下沿是 `tip-before+1`**，锚点开窗的下沿是 `anchor-before`；一律夹到 `floor+1`。
 * 4. **清单说齐全还不够，手里得真有东西**（`localNewest < lo → 问服务端`）；但会话最新位点在下界以内时可见范围内本来就一条没有，直接 local。
 * 5. **tip 未知一律问服务端**：不知道上界就无从判断本地齐不齐。
 *
 * `floor` 用「`≤ floor` 的号对本端不存在」口径（= `ChatTailPlan.visibleFrom - 1`），与 Web 同数轴。
 */
object ChatEntryPlan {

    /** 有没有未读：只认真实未读数。 */
    fun hasUnread(unread: Int): Boolean = unread > 0

    /** 未读时锚到已读位点（最小 1，见类注释第 2 条）；无未读 `0` = 取最新。 */
    fun anchorFor(readSeq: Long, unread: Int): Long = if (hasUnread(unread)) maxOf(1L, readSeq) else 0L

    fun plan(
        readSeq: Long,
        unread: Int,
        /** 会话最新位点（未知 0）。 */
        tip: Long,
        ranges: List<SeqRange>,
        /** 本地**实际存在的消息**里最大的 conv_seq（没有 0）。 */
        localNewest: Long,
        /** `≤ floor` 的号不存在。0 = 无下界。 */
        floor: Long,
        before: Int = ChatWindows.ENTRY_BEFORE,
        after: Int = ChatWindows.ENTRY_AFTER,
        latestPage: Int = ChatWindows.LATEST_FETCH,
    ): EntryPlan {
        val f = maxOf(0L, floor)
        val rawAnchor = anchorFor(readSeq, unread)
        val rawBefore = if (rawAnchor > 0) before else latestPage
        val rawAfter = if (rawAnchor > 0) after else 0
        // 锚点落在下界以内（读位点在被清掉/不可见的那一段里）：抬到下界之上、不再往下带上下文，
        // 否则 window_req 会把用户刚清掉的整页又要回来
        val underFloor = rawAnchor > 0 && rawAnchor <= f
        val anchor = if (underFloor) f + 1 else rawAnchor
        val b = if (underFloor) 0 else rawBefore
        val server = EntryPlan.Server(anchor, b, rawAfter)
        if (tip > 0 && tip <= f) return EntryPlan.Local // 可见范围内一条没有，没有东西可问，也不许问
        if (tip <= 0) return server
        val lo = maxOf(1L, f + 1, if (anchor > 0) anchor - b else tip - b + 1)
        val hi = if (anchor > 0) minOf(tip, anchor + rawAfter) else tip
        if (hi < lo) return server
        if (localNewest < lo) return server
        return if (SyncRanges.coversSpan(ranges, lo, hi)) EntryPlan.Local else server
    }
}
