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

    /**
     * 观察**最近 [limit] 条**（按显示序返回：旧→新）。
     *
     * ## 绝不能无界查询
     * 早先这里是 `SELECT * ... ORDER BY timestamp ASC`（不带 LIMIT），
     * 2026-09-07 实测在一条 **13 万条**的会话上直接把聊天页渲染成空白——
     * 130k 行全被构造成对象，Flow 迟迟发不出第一帧。
     * 这正是 iOS 文档里记的「几十万行全构造成对象」那个瓶颈，本端一样躲不过。
     *
     * 取最新 N 条要 `ORDER BY ... DESC LIMIT n`，**调用方再反转成显示序**——
     * 写成 ASC + LIMIT 会取到最**旧**的 N 条（那是另一个很容易犯的错）。
     */
    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    fun observeWindow(owner: String, convId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message WHERE ownerUid = :owner AND convId = :convId")
    suspend fun countIn(owner: String, convId: String): Int

    /**
     * 会话内搜索（SEARCH_DESIGN §4）：**整个会话查库，不是在渲染窗口里过滤**。
     *
     * 判据逐条镜像后端 G4（`internal/store/sqlite_message.go` 的 `SearchConvMessages`）：
     * `text` 的 content / 任意 caption / file_name 三源子串，排除撤回与删除；
     * 系统消息不参与（它的 content 是全群共享的一句话，搜出来点不进去也没意义）。
     * `:like` 由调用方拼成 `%需求%` 并**已过 [com.libeyond.imandroid.data.ChatSearch.escapeLike]**。
     *
     * 排序与 [observeWindow] 同口径（timestamp 主排），取**最新的 limit 条**——
     * 命中更多时调用方要把计数补 `+`，不能悄悄截断。
     *
     * ⚠️ **不要改成在内存里过滤 `observeWindow` 的结果**：那是"渲染当前屏"用的一窗，
     * 而搜索问的是"整个会话"。im-web 正是这么错过一次——3 万条的群里只命中 98 条
     * （= 窗口条数），界面照常、结果是错的（`current_task.archive.md` 2026-09-01 那条）。
     */
    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND recalledAt IS NULL AND deletedAt IS NULL AND contentType <> 'system'
          AND (
                (contentType = 'text' AND content LIKE :like ESCAPE '\')
             OR (caption IS NOT NULL AND caption <> '' AND caption LIKE :like ESCAPE '\')
             OR (fileName IS NOT NULL AND fileName <> '' AND fileName LIKE :like ESCAPE '\')
          )
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    suspend fun search(owner: String, convId: String, like: String, limit: Int): List<MessageEntity>

    /**
     * 显示序上「这一条及其之后」共有多少条 —— 也就是**要把渲染窗口撑到多大才能包含它**。
     *
     * 比较式必须与 [observeWindow] 的 `ORDER BY timestamp DESC, convSeq DESC` 逐字对应
     * （同毫秒时按 convSeq），否则算出来的窗口会差几条、跳转落空。
     */
    @Query("""
        SELECT COUNT(*) FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND (timestamp > :ts OR (timestamp = :ts AND convSeq >= :seq))
    """)
    suspend fun countAtOrAfter(owner: String, convId: String, ts: Long, seq: Long): Int

    @Query("SELECT MAX(convSeq) FROM message WHERE ownerUid = :owner AND convId = :convId")
    suspend fun maxConvSeq(owner: String, convId: String): Long?

    @Query("SELECT * FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq = :seq")
    suspend fun byConvSeq(owner: String, convId: String, seq: Long): MessageEntity?

    @Query("DELETE FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq = :seq")
    suspend fun delete(owner: String, convId: String, seq: Long)

    @Query("DELETE FROM message WHERE ownerUid = :owner AND convId = :convId")
    suspend fun clearConv(owner: String, convId: String)

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

    @Query("DELETE FROM pending_message WHERE ownerUid = :owner AND convId = :convId")
    suspend fun clearConv(owner: String, convId: String)

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

    /**
     * 推进已读位点。**用 MAX 保证单调不倒退**。
     *
     * 无条件 `readSeq = :seq` 是错的：可见即读上报的是「当前可见的最大 conv_seq」，
     * 用户往回翻历史时这个值会变小，直接赋值等于把已读位点**写回去**——
     * 表现为「翻了下历史，未读又冒出来了」，而且会向服务端发一个倒退的 read 回执。
     */
    @Query("""
        UPDATE conversation
        SET readSeq = MAX(readSeq, :seq), unread = 0, markedUnread = 0
        WHERE ownerUid = :owner AND convId = :convId
    """)
    suspend fun markRead(owner: String, convId: String, seq: Long)

    @Query("SELECT COALESCE(SUM(unread), 0) FROM conversation WHERE ownerUid = :owner AND muted = 0")
    fun observeTotalUnread(owner: String): Flow<Int>

    @Query("DELETE FROM conversation WHERE ownerUid = :owner")
    suspend fun clearAccount(owner: String)
}
