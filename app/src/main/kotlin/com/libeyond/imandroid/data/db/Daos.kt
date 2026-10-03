package com.libeyond.imandroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.libeyond.imandroid.data.SeqPoint
import com.libeyond.imandroid.sdk.protocol.ContentType
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

    /** 同 [observeWindow]，但**下界**固定在 `convSeq >= :fromSeq`——尾窗只取最新那一段，不跨缺口拼旧岛（C4b）。 */
    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId AND convSeq >= :fromSeq
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    fun observeWindowFrom(owner: String, convId: String, fromSeq: Long, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message WHERE ownerUid = :owner AND convId = :convId")
    suspend fun countIn(owner: String, convId: String): Int

    /** `convSeq > :seq` 的本地消息条数（进会话判「首条未读是否就在尾窗里」用）。 */
    @Query("SELECT COUNT(*) FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq > :seq")
    suspend fun countAfter(owner: String, convId: String, seq: Long): Int

    /** `convSeq > :seq` 的第一条本地消息（读位点之后第一条）；没有则 null。 */
    @Query("SELECT * FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq > :seq ORDER BY convSeq ASC LIMIT 1")
    suspend fun nextAfterSeq(owner: String, convId: String, seq: Long): MessageEntity?

    /**
     * 会话媒体时间线（查看器左右翻页用）：**整个会话**里最新的 [limit] 条图片/视频。
     *
     * 与 [observeWindow] 的区别同 [search]：那是"渲染当前屏"的一窗，这问的是"整个会话有哪些图"。
     * 在渲染窗口里过滤出来的序列会让用户看到「这张图前后没有别的图了」——而那是假的
     * （iOS `mediaMessagesForConv:` 的注释记的是同一条）。
     *
     * **排序按 conv_seq，不是 timestamp 主排**：这一条与本文件其余查询刻意不同。翻页序列要与
     * 服务端媒体接口（`conv_seq DESC` 游标分页）拼在一起，两边排序口径不一致的话，续拉回来的
     * 更早一页会插在错的位置上。显示序那条契约管的是聊天列表，这里不涉及。
     *
     * **必须带 limit**（同 [observeWindow] 的那条教训：13 万条的会话整窗构造对象会把页面渲染成空白）。
     */
    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND recalledAt IS NULL AND deletedAt IS NULL
          AND convSeq > 0 AND content <> ''
          AND contentType IN ('${ContentType.IMAGE}', '${ContentType.VIDEO}')
        ORDER BY convSeq DESC
        LIMIT :limit
    """)
    suspend fun convMedia(owner: String, convId: String, limit: Int): List<MessageEntity>

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
          AND (:fromUid = '' OR sender = :fromUid)
          AND (
                :like = ''
             OR (contentType = 'text' AND content LIKE :like ESCAPE '\')
             OR (caption IS NOT NULL AND caption <> '' AND caption LIKE :like ESCAPE '\')
             OR (fileName IS NOT NULL AND fileName <> '' AND fileName LIKE :like ESCAPE '\')
          )
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    suspend fun search(owner: String, convId: String, like: String, fromUid: String, limit: Int): List<MessageEntity>

    /**
     * 首页全局搜索的「聊天记录」：跨**所有会话**的命中（对齐 iOS `searchMessagesMatching:inConv:nil`）。
     * 判据与 [search] 同一套（SQL 收窄 + [com.libeyond.imandroid.data.ChatSearch.matches] 复核）；
     * 不带「来自」过滤，空词不查（调用方保证）。残留会话（已退群/已删）的消息由调用方按会话列表过滤。
     */
    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner
          AND recalledAt IS NULL AND deletedAt IS NULL AND contentType <> 'system'
          AND (
                (contentType = 'text' AND content LIKE :like ESCAPE '\')
             OR (caption IS NOT NULL AND caption <> '' AND caption LIKE :like ESCAPE '\')
             OR (fileName IS NOT NULL AND fileName <> '' AND fileName LIKE :like ESCAPE '\')
          )
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    suspend fun searchAll(owner: String, like: String, limit: Int): List<MessageEntity>

    /**
     * 「来自」候选：本会话**已发过消息**的去重发件人（对齐 iOS `senderCandidatesForConv:`）。
     * **不是群成员表**——没发过言的成员过滤后必 0 命中，列出无意义；系统消息没有真实发送者，排除。
     * 按最后一次发言时间倒序：最近说过话的人排前面，找起来更快。
     */
    @Query("""
        SELECT sender FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND recalledAt IS NULL AND deletedAt IS NULL AND contentType <> 'system' AND sender <> ''
        GROUP BY sender
        ORDER BY MAX(timestamp) DESC
    """)
    suspend fun distinctSenders(owner: String, convId: String): List<String>

    /**
     * 日历「跳到某天 / 今天」共用：从 [fromMs]（含）起本地第一条可见消息的 conv_seq。
     * 目标那天没有消息时自然落到下一个有消息的日子；没有更晚的消息则回 null。
     * **仅本地完整时可信**——见 [com.libeyond.imandroid.data.firstConvSeqAtOrAfter] 的用法说明。
     */
    @Query("""
        SELECT convSeq FROM message
        WHERE ownerUid = :owner AND convId = :convId AND timestamp >= :fromMs
          AND recalledAt IS NULL AND deletedAt IS NULL AND contentType <> 'system' AND convSeq > 0
        ORDER BY timestamp ASC, convSeq ASC
        LIMIT 1
    """)
    suspend fun firstConvSeqAtOrAfter(owner: String, convId: String, fromMs: Long): Long?

    /**
     * 同上，但**限定在 `[fromMs, toMs)` 内**：本地有缺口且拿不到服务端日历时用——
     * 「那天或之后第一条」会跳过缺口静默落到别的日子，限定在当天内才不会答错（没有就说需要联网）。
     */
    @Query("""
        SELECT convSeq FROM message
        WHERE ownerUid = :owner AND convId = :convId AND timestamp >= :fromMs AND timestamp < :toMs
          AND recalledAt IS NULL AND deletedAt IS NULL AND contentType <> 'system' AND convSeq > 0
        ORDER BY timestamp ASC, convSeq ASC
        LIMIT 1
    """)
    suspend fun firstConvSeqBetween(owner: String, convId: String, fromMs: Long, toMs: Long): Long?

    /**
     * 日历打点：本地库里"有消息的整天"集合（本地时区分桶 ms，公式与
     * [com.libeyond.imandroid.data.ChatCalendar.dayStartMs] 逐字一致——同一份 [utcOffsetMs]
     * 算出来的桶才能直接跟服务端 `ConvCalendarDay.dayStartMs` 求并集）。
     * 镜像 iOS `IMDatabase.activeLocalDayStartsInConv:utcOffsetMs:`；本地完整与否都查
     * ——只看当前窗口打点会几乎全灰。过滤口径同 [firstConvSeqAtOrAfter]：撤回/本地删除/系统消息不算"有消息"。
     */
    @Query("""
        SELECT DISTINCT CAST((timestamp + :utcOffsetMs) / 86400000 AS INTEGER) * 86400000 - :utcOffsetMs AS dayStart
        FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND recalledAt IS NULL AND deletedAt IS NULL AND contentType <> 'system' AND timestamp > 0
        ORDER BY dayStart ASC
    """)
    suspend fun activeLocalDayStarts(owner: String, convId: String, utcOffsetMs: Long): List<Long>

    /**
     * 观察一段**锚点窗**（`ChatWindow.Anchored`），返回显示序（旧→新）。
     *
     * 与 [observeWindow] 的区别只有一个：那个是"最近 N 条"（上界开着，新消息会进来），
     * 这个是**两端都闭**的区间——用户正在看历史，新消息不该把他拽走
     * （`MESSAGE_WINDOW_DESIGN` §4.2：跳转即换窗，想回最新走 ↓ 按钮）。
     *
     * 边界比较式必须与 `ORDER BY timestamp, convSeq` 逐字对应，否则在"同毫秒多条"
     * 那一小段上会多取或少取几行。
     */
    @Query("""
        SELECT * FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND (timestamp > :loTs OR (timestamp = :loTs AND convSeq >= :loSeq))
          AND (timestamp < :hiTs OR (timestamp = :hiTs AND convSeq <= :hiSeq))
        ORDER BY timestamp ASC, convSeq ASC
    """)
    fun observeRange(
        owner: String,
        convId: String,
        loTs: Long,
        loSeq: Long,
        hiTs: Long,
        hiSeq: Long,
    ): Flow<List<MessageEntity>>

    /** 显示序上**严格早于**给定坐标的若干行（近→远）。算锚点窗下界用。 */
    @Query("""
        SELECT timestamp, convSeq FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND (timestamp < :ts OR (timestamp = :ts AND convSeq < :seq))
        ORDER BY timestamp DESC, convSeq DESC
        LIMIT :limit
    """)
    suspend fun pointsBefore(owner: String, convId: String, ts: Long, seq: Long, limit: Int): List<SeqPoint>

    /** 显示序上**不早于**给定坐标的若干行（远→近的反向，即正序）。算锚点窗上界用。 */
    @Query("""
        SELECT timestamp, convSeq FROM message
        WHERE ownerUid = :owner AND convId = :convId
          AND (timestamp > :ts OR (timestamp = :ts AND convSeq >= :seq))
        ORDER BY timestamp ASC, convSeq ASC
        LIMIT :limit
    """)
    suspend fun pointsAtOrAfter(owner: String, convId: String, ts: Long, seq: Long, limit: Int): List<SeqPoint>

    /**
     * 历史遗留的 `msg_op` 事件行（本端在 2026-09-09 之前把它们当普通消息落了库）。
     * 供启动时一次性收敛：**先应用它们的效果，再逐条删**（见 `MessageRepository.convergeLegacyMsgOpRows`）。
     *
     * ⚠️ 刻意**没有**配一个「删掉本账号所有 msg_op 行」的 DELETE：取数带 limit，
     * 那种整删会把还没应用的行一起抹掉，等于把那几次撤回/编辑/置顶/删除永久丢掉。
     */
    @Query("SELECT * FROM message WHERE ownerUid = :owner AND contentType = 'msg_op' ORDER BY convSeq ASC LIMIT :limit")
    suspend fun legacyMsgOpRows(owner: String, limit: Int): List<MessageEntity>

    @Query("SELECT MAX(convSeq) FROM message WHERE ownerUid = :owner AND convId = :convId")
    suspend fun maxConvSeq(owner: String, convId: String): Long?

    @Query("SELECT * FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq = :seq")
    suspend fun byConvSeq(owner: String, convId: String, seq: Long): MessageEntity?

    @Query("DELETE FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq = :seq")
    suspend fun delete(owner: String, convId: String, seq: Long)

    /** 批量物理移除：一条语句，Room 只失效一次（逐条 delete 会让观察方跟着刷 N 次）。seqs ≤100（多选上限）。 */
    @Query("DELETE FROM message WHERE ownerUid = :owner AND convId = :convId AND convSeq IN (:seqs)")
    suspend fun deleteSeqs(owner: String, convId: String, seqs: List<Long>)

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

    /** 单聊按对端 uid 找会话行（名片并回用）；群聊行 peerUid 为空，不会命中。 */
    @Query("SELECT * FROM conversation WHERE ownerUid = :owner AND peerUid = :peer AND isGroup = 0 LIMIT 1")
    suspend fun byPeer(owner: String, peer: String): ConversationEntity?

    /** 只改对端资料三列，不动未读 / 游标 / 置顶等别的列（读-改-写整行会盖掉并发的同步进度）。 */
    @Query("UPDATE conversation SET title = :title, avatarUrl = :avatarUrl, peerRemark = :peerRemark WHERE ownerUid = :owner AND convId = :convId")
    suspend fun updatePeerProfile(owner: String, convId: String, title: String, avatarUrl: String, peerRemark: String)

    @Query("SELECT * FROM conversation WHERE ownerUid = :owner")
    suspend fun all(owner: String): List<ConversationEntity>

    /** 游标推进：**只由 [com.libeyond.imandroid.data.SyncCursorRule] 算出的值调用**。 */
    @Query("UPDATE conversation SET syncedConvSeq = :seq WHERE ownerUid = :owner AND convId = :convId")
    suspend fun setSyncedConvSeq(owner: String, convId: String, seq: Long)

    /**
     * 服务端最新位点**只增不减**（[ConversationEntity.headConvSeq]）。会话行还没建时影响 0 行——
     * **刻意不插占位行**，否则列表里会冒出无名空会话（iOS `updateHeadConvSeq` 同款取舍）。
     */
    @Query("UPDATE conversation SET headConvSeq = :head WHERE ownerUid = :owner AND convId = :convId AND headConvSeq < :head")
    suspend fun raiseHead(owner: String, convId: String, head: Long)

    /** 服务端最新位点的变化流（C4b：bump 到了且用户贴底就补最新一页）。会话行不存在时无发射。 */
    @Query("SELECT headConvSeq FROM conversation WHERE ownerUid = :owner AND convId = :convId")
    fun observeHead(owner: String, convId: String): kotlinx.coroutines.flow.Flow<Long>

    /** 本机清空位点**只增不减**（[ConversationEntity.clearedUpTo]）。 */
    @Query("UPDATE conversation SET clearedUpTo = :seq WHERE ownerUid = :owner AND convId = :convId AND clearedUpTo < :seq")
    suspend fun raiseClearedUpTo(owner: String, convId: String, seq: Long)

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

    /** 退群/被移出/解散：这一行从列表消失（[com.libeyond.imandroid.data.MessageRepository.removeConversation]）。 */
    @Query("DELETE FROM conversation WHERE ownerUid = :owner AND convId = :convId")
    suspend fun deleteConv(owner: String, convId: String)

    @Query("DELETE FROM conversation WHERE ownerUid = :owner")
    suspend fun clearAccount(owner: String)
}
