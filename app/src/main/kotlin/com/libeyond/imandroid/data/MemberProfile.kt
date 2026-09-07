package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.UserCard

/**
 * 从「群成员行」推出「个人资料页的初始状态」。
 *
 * 抽成纯函数是因为这两步各踩着一个具体的错：
 * ① [GroupMember.displayName] 优先取**群昵称**，直接拿它当资料卡的 `nickname`，
 *    就是把「他在这个群里叫什么」显示成「他的昵称」——用户改群昵称，别人的资料页跟着变；
 * ② 关系与备注不给种子，进页会先渲染成「陌生人 + 昵称」，拉到名片后再跳成「好友 + 备注」，
 *    正是 2026-08-30 三端收口要消除的那种闪动。
 */
object MemberProfile {

    /** 端上自造的一档：看自己。与服务端 friend status 的取值空间不重叠。 */
    const val RELATION_SELF = "self"

    /**
     * 进页即定型的关系。
     *
     * **自己要单独一档**：不判的话点到自己头上会看到一个「加好友」按钮。
     */
    fun relationOf(memberUid: String, myUid: String, friend: FriendEntry?): String = when {
        memberUid.isNotEmpty() && memberUid == myUid -> RELATION_SELF
        else -> friend?.status.orEmpty()
    }

    /**
     * 资料卡种子。**只放本地已经确定的东西**，其余留空等名片接口补
     * （phone/tags/presence 这些群成员行里根本没有，编不出来）。
     */
    fun seedOf(m: GroupMember, friend: FriendEntry?): UserCard = UserCard(
        userId = m.userId,
        username = m.username,
        // 全局昵称，**不是** m.displayName（那个会优先取 groupNickname）
        nickname = m.nickname,
        avatarUrl = m.avatarUrl,
        remark = friend?.remark.orEmpty(),
    )
}
