package com.libeyond.imandroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 好友 / 群列表的**本地快照**（对齐 iOS `im_friend_local` / `im_group_local`，任务 5「断网回退」）。
 * 每次 `GET /friends`、`GET /groups` 成功后整表覆盖；进页先用它画首屏，再等网络刷新，**拉不到就保留不清空**。
 * 单库多账号：主键带 `ownerUid`。读写入口见 [com.libeyond.imandroid.data.RosterCache]。
 *
 * 好友表**只存 accepted**（pending/requested 是易变的、以服务端为准，不进快照）；`blocked` 与 status 正交，单独一列。
 * 群表**只存列表字段**——成员表 / 简介 / 公告 / 权限开关都不存，进群资料页仍现拉（别把它当成员数据源）。
 */
@Entity(tableName = "friend_local", primaryKeys = ["ownerUid", "userId"])
data class FriendLocalEntity(
    val ownerUid: String,
    val userId: String,
    /** 服务端返回顺序（保持原序渲染）。 */
    val sortOrder: Int,
    val username: String,
    val nickname: String,
    val remark: String,
    val avatarUrl: String,
    val blocked: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "group_local", primaryKeys = ["ownerUid", "convId"])
data class GroupLocalEntity(
    val ownerUid: String,
    val convId: String,
    val sortOrder: Int,
    val name: String,
    val avatarUrl: String,
    val owner: String,
    val ownerNickname: String,
    val ownerUsername: String,
    val createdAt: Long,
    /** 服务端原样字符串（owner|admin|member）。 */
    val myRole: String,
    val memberCount: Int,
    val isSuper: Boolean,
)

@Dao
interface FriendLocalDao {
    @Query("SELECT * FROM friend_local WHERE ownerUid = :owner ORDER BY sortOrder ASC")
    suspend fun list(owner: String): List<FriendLocalEntity>

    @Query("DELETE FROM friend_local WHERE ownerUid = :owner")
    suspend fun clear(owner: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FriendLocalEntity>)
}

@Dao
interface GroupLocalDao {
    @Query("SELECT * FROM group_local WHERE ownerUid = :owner ORDER BY sortOrder ASC")
    suspend fun list(owner: String): List<GroupLocalEntity>

    @Query("DELETE FROM group_local WHERE ownerUid = :owner")
    suspend fun clear(owner: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<GroupLocalEntity>)
}
