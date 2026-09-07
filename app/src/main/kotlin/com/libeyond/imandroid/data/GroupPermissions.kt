package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember

/**
 * 群管理权限判据（G1/G2，PROTOCOL §11）。
 *
 * **服务端永远是权威**，这里只决定「按钮显不显、点不点得动」。抽成纯函数的理由：
 * 这几条规则会同时出现在**成员长按菜单、群资料页管理入口、成员详情页**三个地方，
 * 散着写必然分叉——分叉的表现是「按钮亮着但点了报 300204」或者反过来
 * 「明明有权限却看不到入口」，两种都很难自查，因为两条路各自看都"对"。
 *
 * 核心是一条**等级序**：owner > admin > member。「须严格高于对方」这句话在
 * 踢人、禁言、改角色三处反复出现，这里只实现一次（[outranks]）。
 */
object GroupPermissions {

    /** 角色等级。数越大权越高；未知角色按最低处理（新服务端加了角色时端上不会误放行）。 */
    fun rank(role: String): Int = when (role) {
        GroupMember.ROLE_OWNER -> 2
        GroupMember.ROLE_ADMIN -> 1
        else -> 0
    }

    /**
     * 我是否**严格高于**对方。踢人 / 禁言 / 改角色都用它。
     *
     * 「严格」是要点：两个管理员之间互相踢不动，否则一个群里的管理员可以互相清场。
     */
    fun outranks(myRole: String, theirRole: String): Boolean = rank(myRole) > rank(theirRole)

    /** 改群名/头像/简介：群主/管理员；普通成员在「允许成员改群资料」开关打开时也可以。 */
    fun canEditInfo(info: GroupInfo, permEditInfo: Boolean): Boolean =
        info.iAmManager || permEditInfo

    /** 发布/撤下公告：仅群主/管理员（**不受 perm_edit_info 影响**，公告比资料重）。 */
    fun canEditAnnouncement(info: GroupInfo): Boolean = info.iAmManager

    /** 全员禁言 / 群治理开关：仅群主/管理员。 */
    fun canMuteAll(info: GroupInfo): Boolean = info.iAmManager
    fun canEditSettings(info: GroupInfo): Boolean = info.iAmManager

    /** 踢人：须是管理层**且严格高于对方**，且不能踢自己（退群走另一条路）。 */
    fun canRemove(info: GroupInfo, target: GroupMember, myUid: String): Boolean =
        info.iAmManager && target.userId != myUid && outranks(info.myRole, target.role)

    /** 禁言某人：同踢人。 */
    fun canMute(info: GroupInfo, target: GroupMember, myUid: String): Boolean =
        canRemove(info, target, myUid)

    /** 设/撤管理员：**仅群主**，且目标不是自己。 */
    fun canSetRole(info: GroupInfo, target: GroupMember, myUid: String): Boolean =
        info.myRole == GroupMember.ROLE_OWNER && target.userId != myUid

    /** 转让群主：仅群主，且目标不是自己。 */
    fun canTransfer(info: GroupInfo, target: GroupMember, myUid: String): Boolean =
        canSetRole(info, target, myUid)

    /** 审批入群申请：群主/管理员。 */
    fun canReviewJoin(info: GroupInfo): Boolean = info.iAmManager

    /**
     * 能不能直接退群。
     *
     * **群主必须先转让**（服务端同规则）——不挡的话用户点了退群拿到一个错误码，
     * 而正确的引导是「先把群交给别人」。
     */
    fun canLeave(info: GroupInfo): Boolean = info.myRole != GroupMember.ROLE_OWNER

    /** 邀请人入群：所有人可邀，除非群开了「仅管理员可邀请」。 */
    fun canInvite(info: GroupInfo): Boolean = info.permInvite || info.iAmManager

    /** 我现在是不是被禁言（全员禁言或单独禁言）。`-1` 是永久。 */
    fun amMuted(info: GroupInfo, now: Long = System.currentTimeMillis()): Boolean {
        if (info.iAmManager) return false          // 管理层不受全员禁言限制
        return isMuteActive(info.muteUntil, now) || isMuteActive(info.myMuteUntil, now)
    }

    /** 一个 mute_until 值现在是否生效。`0`=没禁 / `-1`=永久 / 其余=到期毫秒。 */
    fun isMuteActive(until: Long, now: Long = System.currentTimeMillis()): Boolean =
        until == -1L || (until > 0 && until > now)
}
