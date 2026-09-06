package com.libeyond.imandroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * 幂等写入。`REPLACE` 是刻意的：sync 重复补拉同一条时要收敛成一条
     * （幂等键 `(ownerUid, convId, convSeq)`，PROTOCOL §6.2）。
     */
    @Upsert
    suspend fun upsert(messages: List<MessageEntity>)

    @Upsert
    suspend fun upsert(message: MessageEntity)

    /**
     * 按**时间戳主排**取一窗。
     *
     * 排序口径是三端共同契约：`timestamp` 主排，同毫秒按 `convSeq`。
     * iOS 2026-08-05 踩过——DB 用 `CASE WHEN conv_seq>0 THEN 0 ELSE 1` 把 conv_seq=0
     * 甩到末尾，被拒收的消息永久钉底，用户以为没收到。本端待发消息不在这张表里
     * （见 [PendingMessageEntity]），故不需要那个特判，但**排序口径仍须一致**。
     */
    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    suspend fun latestWindow(owner: String, convId: String, limit: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId AND convSeq < :beforeSeq
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    suspend fun olderThan(owner: String, convId: String, beforeSeq: Long, limit: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId
        ORDER BY timestamp ASC, convSeq ASC
    """)
    fun observeAll(owner: String, convId: String): Flow<List<MessageEntity>>

    @Query("SELECT MAX(convSeq) FROM message WHERE ownerUid = :owner AND convId = :convId")
    suspend fun maxConvSeq(owner: String, convId: String): Long?

    @Query("SELECT * FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq = :seq")
    suspend fun byConvSeq(owner: String, convId: String, seq: Long): MessageEntity?

    @Query("DELETE FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq = :seq")
    suspend fun delete(owner: String, convId: String, seq: Long)

    @Query("DELETE FROM message WHERE ownerUid = :owner")
    suspend fun clearAccount(owner: String)
}

@Dao
interface PendingMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(msg: PendingMessageEntity)

    @Query("SELECT * FROM pending_message WHERE ownerUid = :owner AND convId = :convId ORDER BY createdAt ASC")
    fun observe(owner: String, convId: String): Flow<List<PendingMessageEntity>>

    @Query("SELECT * FROM pending_message WHERE ownerUid = :owner AND state = 'Sending' ORDER BY createdAt ASC")
    suspend fun inFlight(owner: String): List<PendingMessageEntity>

    @Query("SELECT * FROM pending_message WHERE ownerUid = :owner AND clientMsgId = :cid")
    suspend fun byClientId(owner: String, cid: String): PendingMessageEntity?

    @Query("UPDATE pending_message SET state = :state, errorCode = :code WHERE ownerUid = :owner AND clientMsgId = :cid")
    suspend fun markState(owner: String, cid: String, state: String, code: Int)

    /** ack 到达后从待发表移除（真身已落进 message 表）。 */
    @Query("DELETE FROM pending_message WHERE ownerUid = :owner AND clientMsgId = :cid")
    suspend fun remove(owner: String, cid: String)

    @Query("DELETE FROM pending_message WHERE ownerUid = :owner")
    suspend fun clearAccount(owner: String)
}

@Dao
interface ConversationDao {

    @Upsert
    suspend fun upsert(conv: ConversationEntity)

    @Upsert
    suspend fun upsert(convs: List<ConversationEntity>)

    /** 会话列表：置顶优先，再按最后活跃倒序。 */
    @Query("""
        SELECT * FROM conversation
        WHERE ownerUid = :owner
        ORDER BY pinnedAt DESC, lastTimestamp DESC
    """)
    fun observeList(owner: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE ownerUid = :owner AND convId = :convId")
    suspend fun byId(owner: String, convId: String): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE ownerUid = :owner")
    suspend fun all(owner: String): List<ConversationEntity>

    /** 游标推进：**只由 [com.libeyond.imandroid.data.SyncCursorRule] 算出的值调用**。 */
    @Query("UPDATE conversation SET syncedConvSeq = :seq WHERE ownerUid = :owner AND convId = :convId")
    suspend fun setSyncedConvSeq(owner: String, convId: String, seq: Long)

    @Query("UPDATE conversation SET readSeq = :seq, unread = 0, markedUnread = 0 WHERE ownerUid = :owner AND convId = :convId")
    suspend fun markRead(owner: String, convId: String, seq: Long)

    @Query("SELECT COALESCE(SUM(unread), 0) FROM conversation WHERE ownerUid = :owner AND muted = 0")
    fun observeTotalUnread(owner: String): Flow<Int>

    @Query("DELETE FROM conversation WHERE ownerUid = :owner")
    suspend fun clearAccount(owner: String)
}
