package com.libeyond.imandroid.data

/** 闭区间 `[lo, hi]`，`1 ≤ lo ≤ hi`，均为 `conv_seq`。 */
data class SeqRange(val lo: Long, val hi: Long)

/**
 * 「本地有哪几段」区间清单的**纯逻辑**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.2）。
 *
 * 本地库从「整本账的副本」变成「看过的页的复印件 + 一张目录」，这里是那张目录的代数：
 * 一组按 `conv_seq` 的闭区间，**互不相交、升序、相邻即合并**。
 *
 * 单独成文件并配单测，是因为「本地齐不齐」一旦算错，上层就拿一段残缺数据去回答「整个会话」的问题，
 * 而这种错误**不报错、不崩溃，只是答案悄悄不对**。
 *
 * ## 两条硬规矩（iOS / Web 都踩过）
 * - **覆盖只认「同一段完整盖住」**（[coversSpan]），不看 `conv_seq` 连不连号：连号≠齐全，
 *   占号却不成消息的行（`msg_op` 事件行、墓碑、对我不可见的行）会让「最大 seq == head」撒谎。
 * - 相邻（`hi + 1 == lo`）也要并：`conv_seq` 是连续整数，留成两段会让「齐全」永远判假。
 *
 * 对称兄弟：im-web `src/sdk/ranges.ts`、iOS `IMDatabase+Ranges.m`（`SYMMETRY.md` 已登记）。
 */
object SyncRanges {

    /** 归一化：过滤非法项 → 升序 → 合并重叠与相邻。 */
    fun normalize(ranges: List<SeqRange>): List<SeqRange> {
        val out = mutableListOf<SeqRange>()
        for (r in ranges.filter { it.lo >= 1 && it.hi >= it.lo }.sortedBy { it.lo }) {
            val last = out.lastOrNull()
            if (last != null && r.lo <= last.hi + 1) {
                if (r.hi > last.hi) out[out.lastIndex] = SeqRange(last.lo, r.hi)
            } else {
                out += r
            }
        }
        return out
    }

    /** 登记一段「我已齐全」，返回归一化后的新清单（不改入参）。非法入参当无事发生。 */
    fun add(ranges: List<SeqRange>, lo: Long, hi: Long): List<SeqRange> {
        if (hi < lo || hi < 1) return normalize(ranges)
        return normalize(ranges + SeqRange(maxOf(1L, lo), hi))
    }

    fun hasSeq(ranges: List<SeqRange>, seq: Long): Boolean = ranges.any { seq in it.lo..it.hi }

    /** 整段 `[lo, hi]` 是否被**同一个**区间完整覆盖（跨两段说明中间有缺口，不算）。 */
    fun coversSpan(ranges: List<SeqRange>, lo: Long, hi: Long): Boolean =
        hi >= lo && ranges.any { it.lo <= lo && it.hi >= hi }

    fun rangeContaining(ranges: List<SeqRange>, seq: Long): SeqRange? = ranges.firstOrNull { seq in it.lo..it.hi }

    /**
     * 本地是否**齐全**：从 `floor+1` 一路连续到 `head`。
     * `floor` 是可见下界（`history_visible` 把新成员的下界抬到入群位点）；`head ≤ floor`（可见范围内一条没有）视为齐全。
     * ⚠️ `head` 未知（≤0）也落在这一支返回 true：**调用方必须自己排除「齐全但一条都没有」**
     * （iOS 首次登录因此得到过永久空白页）。
     */
    fun isComplete(ranges: List<SeqRange>, head: Long, floor: Long = 0): Boolean =
        head <= floor || coversSpan(ranges, floor + 1, head)

    /** 「从 `floor` 起连续到哪」——`syncedConvSeq` 的等价物，由清单**派生**，不另存一份。 */
    fun contiguousUpTo(ranges: List<SeqRange>, floor: Long = 0): Long =
        ranges.firstOrNull { it.lo <= floor + 1 && it.hi > floor }?.hi ?: floor

    /** 一批 `conv_seq` 的上下界；没有合法序号返回 null。窗口应答据此登记「这一窗服务端给过了」。 */
    fun spanOf(seqs: List<Long>): SeqRange? {
        val valid = seqs.filter { it > 0 }
        return if (valid.isEmpty()) null else SeqRange(valid.min(), valid.max())
    }

    /**
     * 把 `[lo, hi]` 并进与它**重叠或相邻**的那几段，返回合并后的**一整段**。
     * 存储层用它：`overlapping` 是 DAO 按同一个谓词（`hi >= lo-1 AND lo <= hi+1`）查出的既有段。
     */
    fun mergeInto(overlapping: List<SeqRange>, lo: Long, hi: Long): SeqRange =
        SeqRange(
            minOf(maxOf(1L, lo), overlapping.minOfOrNull { it.lo } ?: Long.MAX_VALUE),
            maxOf(hi, overlapping.maxOfOrNull { it.hi } ?: Long.MIN_VALUE),
        )
}
