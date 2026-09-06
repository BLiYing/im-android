package com.libeyond.imandroid.data

/**
 * 同步游标推进规则——**三端共同契约**（`../IMServer/docs/PROTOCOL.md` §6.2「连续游标规则」）。
 *
 * 这是本工程被明文记过账的一条判据，抽成纯函数是因为**用错了会静默烂掉，而且很难看出来**。
 *
 * ## 只认 `covered_conv_seq`
 * 服务端断言 `(since, covered]` 区间内每个 `conv_seq` 要么已在本页下发、要么**对请求者本就不可见**
 * （`history_visible` 把下界抬到入群位点、「仅为我删除」的隐藏项、msg_op 事件行、墓碑）。
 * 客户端把本页消息按序全部落库成功后，游标直接推进到 `covered`，**从而跨过这些永远拿不到的空洞**。
 *
 * ## 四种「看起来也行」的错误做法，全都不行
 * | 错误依据 | 为什么不行 |
 * |---|---|
 * | `latest_conv_seq` | 只覆盖**实际下发**的消息，不覆盖被可见性过滤跳过的序号。游标会永久卡在第一个空洞前一位，**每次 sync 空转重拉同一页**——iOS 2026-08-13 实测因此对某群连刷一天多，且后续消息还会漏收 |
 * | 本地 `MAX(conv_seq)` | 证明不了中间没有空洞 |
 * | 会话列表的最新序号 | 同上，且它来自另一条数据通路 |
 * | 单条发送的 ACK | 只说明这一条到了 |
 *
 * ## 落库失败只推进到首个失败序号之前
 * 其余靠退避重拉幂等收敛（幂等键 `(owner_uid, conv_id, conv_seq)`）。**宁可重拉，不能漏拉。**
 */
object SyncCursorRule {

    /**
     * 算出本页处理完后的新游标。
     *
     * @param current 当前已连续处理完成的位置
     * @param covered 服务端下发的 `covered_conv_seq`；老服务端为 0
     * @param firstFailedSeq 落库失败的首个序号；全部成功传 null
     * @return 新游标。**永不倒退**。
     */
    fun advance(current: Long, covered: Long, firstFailedSeq: Long? = null): Long {
        if (firstFailedSeq != null) {
            // 只推进到首个失败序号之前——那一条之后的都还没可靠落库。
            // 仍要 coerceAtLeast(current)：失败序号可能就在游标处，此时原地不动。
            return (firstFailedSeq - 1).coerceAtLeast(current)
        }
        // 老服务端不带 covered（=0）时取 max(current, 0) 即保持原位、不倒退（向后兼容）。
        return maxOf(current, covered)
    }

    /**
     * 本会话是否还要继续拉。
     * `has_more` 为真时以**新游标**（即 covered）作为下一次的 `since`，不是 latest。
     */
    fun nextSince(newCursor: Long): Long = newCursor
}
