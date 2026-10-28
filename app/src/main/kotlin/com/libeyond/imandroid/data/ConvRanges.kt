package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConvRangeDao
import com.libeyond.imandroid.data.db.ConvRangeEntity

/**
 * 区间清单的读写（C1）。**纯代数在 [SyncRanges]，这里只管落库。**
 *
 * [register] 自己**不开事务**：I1 要求「写消息 + 登记区间」同一事务，由调用方（`MessageRepository` 的写路径）
 * 用 [com.libeyond.imandroid.data.db.DbTx] 把它们包在一起。
 */
class ConvRanges(private val dao: ConvRangeDao) {

    suspend fun ranges(owner: String, convId: String): List<SeqRange> =
        dao.forConv(owner, convId).map { SeqRange(it.lo, it.hi) }

    /** 本会话 `[lo, hi]` 是否被**同一段**完整覆盖。 */
    suspend fun covers(owner: String, convId: String, lo: Long, hi: Long): Boolean =
        SyncRanges.coversSpan(ranges(owner, convId), lo, hi)

    /**
     * 登记一段已齐全的 `[lo, hi]`：与它重叠或相邻的既有段全部并成一整段。
     * 无效入参（`hi < lo`、`hi < 1`）当无事发生。
     */
    suspend fun register(owner: String, convId: String, lo: Long, hi: Long) {
        if (hi < lo || hi < 1) return
        val from = maxOf(1L, lo) - 1
        val to = hi + 1
        val near = dao.overlapping(owner, convId, from, to).map { SeqRange(it.lo, it.hi) }
        val merged = SyncRanges.mergeInto(near, lo, hi)
        dao.deleteOverlapping(owner, convId, from, to)
        dao.insert(ConvRangeEntity(owner, convId, merged.lo, merged.hi))
    }

    /** 位点回退：丢掉越过 [head] 的部分（整段删、跨界段截断）。 */
    suspend fun truncateAbove(owner: String, convId: String, head: Long) {
        dao.deleteAbove(owner, convId, head)
        dao.clampHi(owner, convId, head)
    }

    /** 清到位点但保留其上的消息：区间清单裁到 `floor+1` 起（[SyncRanges.clipAfter]），与消息删除同一事务。 */
    suspend fun clipAfter(owner: String, convId: String, floor: Long) {
        val cur = ranges(owner, convId)
        val next = SyncRanges.clipAfter(cur, floor)
        if (next == cur) return
        dao.clearConv(owner, convId)
        next.forEach { dao.insert(ConvRangeEntity(owner, convId, it.lo, it.hi)) }
    }

    suspend fun clearConv(owner: String, convId: String) = dao.clearConv(owner, convId)

    /** 独立位点表里的位点（0 = 无）。行删了仍在。 */
    suspend fun clearFloor(owner: String, convId: String): Long = dao.clearFloor(owner, convId) ?: 0L

    /** 只增不减地抬位点；调用方包在事务里。 */
    suspend fun raiseClearFloor(owner: String, convId: String, seq: Long) {
        if (seq <= 0) return
        dao.ensureClearFloor(owner, convId)
        dao.raiseClearFloor(owner, convId, seq)
    }

    suspend fun clearAccount(owner: String) {
        dao.clearAccount(owner)
        dao.clearFloorAccount(owner)
    }
}
