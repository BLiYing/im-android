package com.libeyond.imandroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 区间清单的一行：本会话本地「已齐全」的一段 `[lo, hi]`（`conv_seq` 闭区间，一段一行）。
 * 逻辑见 [com.libeyond.imandroid.data.SyncRanges]；读写入口是 [com.libeyond.imandroid.data.ConvRanges]。
 *
 * **不变量 I1**：区间只断言「服务端已给全这段、且已落库」，写消息与登记区间同一事务。
 * 区间**含**占号但不成消息的行（`msg_op` 事件行、墓碑、对我不可见的行）——那些同样是「服务端给过了」。
 */
@Entity(tableName = "conv_range_local", primaryKeys = ["ownerUid", "convId", "lo"])
data class ConvRangeEntity(
    val ownerUid: String,
    val convId: String,
    val lo: Long,
    val hi: Long,
)

@Dao
interface ConvRangeDao {
    @Query("SELECT * FROM conv_range_local WHERE ownerUid = :owner AND convId = :convId ORDER BY lo ASC")
    suspend fun forConv(owner: String, convId: String): List<ConvRangeEntity>

    /** 与 `[from, to]` 重叠**或相邻**的既有段（调用方传 `lo-1`、`hi+1`）。合并与删除必须用同一个谓词。 */
    @Query("SELECT * FROM conv_range_local WHERE ownerUid = :owner AND convId = :convId AND hi >= :from AND lo <= :to")
    suspend fun overlapping(owner: String, convId: String, from: Long, to: Long): List<ConvRangeEntity>

    @Query("DELETE FROM conv_range_local WHERE ownerUid = :owner AND convId = :convId AND hi >= :from AND lo <= :to")
    suspend fun deleteOverlapping(owner: String, convId: String, from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ConvRangeEntity)

    /** 位点回退：整段落在新位点之上的区间删掉。 */
    @Query("DELETE FROM conv_range_local WHERE ownerUid = :owner AND convId = :convId AND lo > :head")
    suspend fun deleteAbove(owner: String, convId: String, head: Long)

    /** 位点回退：跨过新位点的区间截到新位点。 */
    @Query("UPDATE conv_range_local SET hi = :head WHERE ownerUid = :owner AND convId = :convId AND hi > :head AND lo <= :head")
    suspend fun clampHi(owner: String, convId: String, head: Long)

    @Query("DELETE FROM conv_range_local WHERE ownerUid = :owner AND convId = :convId")
    suspend fun clearConv(owner: String, convId: String)

    @Query("DELETE FROM conv_range_local WHERE ownerUid = :owner")
    suspend fun clearAccount(owner: String)
}
