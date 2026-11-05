package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.DeletedUser
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo

/**
 * 分享选择页里「我的好友 / 我的群聊」的一个候选（IMServer `docs/design/sketches/SHARE_PICKER_UX_SKETCH.html`）。
 *
 * [conv] 是**与最近聊天同一种形状**的目标（convId / isGroup / peerUid / 名字 / 头像），
 * 所以调用方（转发、名片、群邀请…）拿到的还是 `List<ConversationEntity>`、一行不用改；
 * 没聊过的好友、会话被删的群也能发——发送路径只读 convId 与 peerUid，ack 落库时会自己造会话行。
 * [keys] 是搜索要匹配的字段（好友：显示名 + 昵称 + @用户名；群：群名）。
 */
data class PickEntry(val conv: ConversationEntity, val keys: List<String>)

/**
 * 选择页的两份额外数据源。`null` = 还没有数据：[friendsFailed]/[groupsFailed] 为真时是「拉取失败且无离线快照」
 * （子页显示「加载失败，点击重试」，点了调 [retryFriends]/[retryGroups]），否则是还在加载（不能抢答「没有好友」）。
 */
data class SharePickSources(
    val friends: List<PickEntry>? = null,
    val groups: List<PickEntry>? = null,
    val friendsFailed: Boolean = false,
    val groupsFailed: Boolean = false,
    val retryFriends: () -> Unit = {},
    val retryGroups: () -> Unit = {},
)

/** 主页搜索结果的三个分组（最近聊天 / 好友 / 群聊）。 */
data class SharePickSections(
    val recent: List<ConversationEntity>,
    val friends: List<ConversationEntity>,
    val groups: List<ConversationEntity>,
) {
    val isEmpty: Boolean get() = recent.isEmpty() && friends.isEmpty() && groups.isEmpty()
}

/**
 * 三处列表**共用的一份**已选集合，以 convId 为唯一身份（同一个人在「最近聊天」与「我的好友」里
 * 勾选态一致，不会选两次、发两份）。
 *
 * [picked] 记下勾选那一刻的目标（这样从好友页勾的人即使不在最近聊天里，发送时也拿得到 peerUid）；
 * 它是插入有序的（Kotlin `Map + / -` 产出 LinkedHashMap），[ids] 即勾选顺序（= 已选栏顺序 = 发送顺序）。
 */
data class SharePickSelection(
    val picked: Map<String, ConversationEntity> = emptyMap(),
) {
    val ids: List<String> get() = picked.keys.toList()

    val size: Int get() = picked.size

    operator fun contains(convId: String): Boolean = convId in picked

    /** 勾 / 取消勾。**超上限返回 null**（调用方吐司、不加入）；取消永远允许，否则选满就卡死。 */
    fun toggle(target: ConversationEntity, max: Int = Forward.MAX_TARGETS): SharePickSelection? {
        if (target.convId in picked) return remove(target.convId)
        if (picked.size >= max) return null
        return SharePickSelection(picked + (target.convId to target))
    }

    fun remove(convId: String): SharePickSelection =
        if (convId !in picked) this else SharePickSelection(picked - convId)

    /**
     * 按勾选顺序取回发送目标。本地有这条会话就用会话行（名字/头像以列表为准），
     * 没有（没聊过的好友、会话已删的群）就用勾选时记下的那一份。
     */
    fun targets(recent: List<ConversationEntity>): List<ConversationEntity> {
        val byId = recent.associateBy { it.convId }
        return picked.map { (id, t) -> byId[id] ?: t }
    }
}

object SharePick {

    /**
     * 「我的好友」：只要 accepted、**不含拉黑、已注销、系统通知账号**，也不含我自己。
     * convId 按 [ConvIds.privateOf] 本地推，与最近聊天里的同一个人对得上。
     */
    fun friendEntries(owner: String, friends: List<FriendEntry>): List<PickEntry> =
        friends.asSequence()
            .filter { f ->
                f.status == FriendEntry.ACCEPTED && !f.blocked && !f.deleted &&
                    f.userId.isNotBlank() && f.userId != owner && !DetailActions.isSystemPeer(f.userId) &&
                    // 离线快照（RosterCache）不带 deleted 字段，靠本进程登记过的注销名单兜住
                    !DeletedUser.has(f.userId)
            }
            .distinctBy { it.userId }
            .map { f ->
                val name = f.displayName
                PickEntry(
                    conv = ConversationEntity(
                        ownerUid = owner,
                        convId = ConvIds.privateOf(owner, f.userId),
                        isGroup = false,
                        peerUid = f.userId,
                        title = name,
                        avatarUrl = f.avatarUrl,
                    ),
                    keys = listOf(name, f.nickname, f.username),
                )
            }
            .toList()

    /**
     * 「我的群聊」：`GET /groups` 是我加入的群（含会话已被删的），convId 由服务端给。
     * 群列表接口不带禁言/角色，所以**不置灰任何群**（2026-11-05 拍板）。
     */
    fun groupEntries(owner: String, groups: List<GroupInfo>): List<PickEntry> =
        groups.asSequence()
            .filter { it.convId.isNotBlank() }
            .distinctBy { it.convId }
            .map { g ->
                PickEntry(
                    conv = ConversationEntity(
                        ownerUid = owner,
                        convId = g.convId,
                        isGroup = true,
                        title = g.name,
                        avatarUrl = g.avatarUrl,
                    ),
                    keys = listOf(g.name),
                )
            }
            .toList()

    /** 子页过滤：按 [PickEntry.keys] 匹配搜索词，去掉调用方排除的会话。 */
    fun filter(entries: List<PickEntry>, query: String, exclude: Set<String> = emptySet()): List<ConversationEntity> =
        entries.filter { it.conv.convId !in exclude && ListSearch.matches(query, it.keys) }.map { it.conv }

    /**
     * 主页搜索：同时搜最近聊天、好友、群聊，分组显示。**已出现在「最近聊天」分组里的人或群，
     * 不在好友/群聊分组里重复出现**（按 convId 去重）。
     *
     * @param recentMatched 已按搜索词过滤好的最近聊天行（转发用 [Forward.pickable]）。
     */
    fun searchSections(
        recentMatched: List<ConversationEntity>,
        sources: SharePickSources,
        query: String,
        exclude: Set<String> = emptySet(),
    ): SharePickSections {
        val shown = recentMatched.mapTo(HashSet()) { it.convId } + exclude
        return SharePickSections(
            recent = recentMatched,
            friends = filter(sources.friends.orEmpty(), query, shown),
            groups = filter(sources.groups.orEmpty(), query, shown),
        )
    }
}
