package com.libeyond.imandroid.data

/**
 * 消息显示序——**三端共享的不变式**（`../IMServer/docs/SYMMETRY.md` 登记表第 45/46 条）：
 *
 * > `timestamp` 主排；同毫秒时 `conv_seq = 0`（待发 / 失败）视为 **+∞ 垫底**。
 *
 * 对端实现（**要一致的是这条口径，不是代码形状**）：
 * - iOS：`IMDatabase.m` 的 `kIMMessageOrderAsc`
 *   （`ORDER BY timestamp ASC, CASE WHEN conv_seq>0 THEN conv_seq ELSE 9223372036854775807 END ASC, row_id ASC`）
 *   与 `IMChatViewController+Socket.m` 的 `sortMessagesInPlace`
 * - im-web：`App.tsx` 的
 *   `(a.timestamp - b.timestamp) || ((a.convSeq || MAX_SAFE_INTEGER) - (b.convSeq || MAX_SAFE_INTEGER))`
 *
 * ### 为什么「conv_seq=0 垫底」不能写成「conv_seq=0 一律排最后」
 * iOS 2026-08-05 就是这么写的，出了事故，注释里留着原话：
 * **「从『临时垫底』变成『永久钉底』」**。本意是让刚发出、还没 ack 的消息显示在最底部，
 * 但**被拒收的消息永远 conv_seq=0**——于是后续收到的消息全插到它上面，
 * 用户滚到底只看到那条旧的失败消息，误以为新消息没收到。
 *
 * 正确的读法是：垫底只在**同一毫秒内**生效；跨时间一律按 `timestamp` 落位。
 *
 * 本端 2026-09-07 撞见同一形状的另一种写法：`buildChatRows` 把待发那一路
 * **整段接在已确认之后**，等价于「conv_seq=0 一律排最后」。已改为合流后统一排序。
 */
object MessageOrder {

    /**
     * 排序用的 seq 键：`conv_seq = 0`（还没有服务端序号）视为 [Long.MAX_VALUE]。
     * 负数不该出现，真出现了也按「未定序」处理——排到最前面比垫底更糟。
     */
    fun seqKey(convSeq: Long): Long = if (convSeq > 0) convSeq else Long.MAX_VALUE

    /** 负 / 0 / 正，语义同 [Comparator.compare]。 */
    fun compare(tsA: Long, seqA: Long, tsB: Long, seqB: Long): Int {
        if (tsA != tsB) return tsA.compareTo(tsB)
        return seqKey(seqA).compareTo(seqKey(seqB))
    }
}
