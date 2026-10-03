package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.DbTx
import com.libeyond.imandroid.data.db.FriendLocalDao
import com.libeyond.imandroid.data.db.FriendLocalEntity
import com.libeyond.imandroid.data.db.GroupLocalDao
import com.libeyond.imandroid.data.db.GroupLocalEntity
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo

/**
 * 好友 / 群列表的离线快照读写（对齐 iOS `IMDatabase+RosterCache`）。
 *
 * - **写**：每次网络拉取成功后整表覆盖（DELETE + INSERT 同一事务），**空列表也写**（= 确实没有）。
 *   好友只写 accepted；写失败整个事务回滚、**不更新指纹**，下次刷新会重试。
 *   好友表按内容指纹跳过「没变」的写（指纹不含顺序之外的东西以外的字段全含，列一变这里要同步改，见 [fingerprint]）。
 * - **读**：进页同步当首屏种子；之后网络刷新覆盖。**拉不到（断网/5xx）保留已画的、不清空**——这是整件事的目的。
 */
class RosterCache(
    private val friends: FriendLocalDao,
    private val groups: GroupLocalDao,
    private val tx: DbTx,
) {
    private val friendPrints = HashMap<String, Int>()

    suspend fun cachedFriends(owner: String): List<FriendEntry> =
        friends.list(owner).map {
            FriendEntry(
                userId = it.userId, username = it.username, nickname = it.nickname, remark = it.remark,
                avatarUrl = it.avatarUrl, status = FriendEntry.ACCEPTED, updatedAt = it.updatedAt, blocked = it.blocked,
            )
        }

    suspend fun saveFriends(owner: String, list: List<FriendEntry>) {
        val accepted = list.filter { it.status == FriendEntry.ACCEPTED }
        val print = fingerprint(accepted)
        if (friendPrints[owner] == print) return
        tx.run {
            friends.clear(owner)
            friends.insertAll(
                accepted.mapIndexed { i, f ->
                    FriendLocalEntity(owner, f.userId, i, f.username, f.nickname, f.remark, f.avatarUrl, f.blocked, f.updatedAt)
                },
            )
        }
        friendPrints[owner] = print // 事务成功才记，失败保留旧指纹让下次重试
    }

    suspend fun cachedGroups(owner: String): List<GroupInfo> =
        groups.list(owner).map {
            GroupInfo(
                convId = it.convId, name = it.name, owner = it.owner, ownerNickname = it.ownerNickname,
                ownerUsername = it.ownerUsername, avatarUrl = it.avatarUrl, createdAt = it.createdAt,
                myRole = it.myRole, memberCount = it.memberCount, isSuper = it.isSuper,
            )
        }

    suspend fun saveGroups(owner: String, list: List<GroupInfo>) {
        tx.run {
            groups.clear(owner)
            groups.insertAll(
                list.mapIndexed { i, g ->
                    GroupLocalEntity(
                        owner, g.convId, i, g.name, g.avatarUrl, g.owner, g.ownerNickname, g.ownerUsername,
                        g.createdAt, g.myRole, g.memberCount, g.isSuper,
                    )
                },
            )
        }
    }

    companion object {
        /** 好友内容指纹：**顺序之外的每一列都参与**（加列忘了改这里 = 那一列的变化不会被落库）。 */
        fun fingerprint(list: List<FriendEntry>): Int =
            list.map { listOf(it.userId, it.username, it.nickname, it.remark, it.avatarUrl, it.blocked, it.updatedAt) }.hashCode()
    }
}
