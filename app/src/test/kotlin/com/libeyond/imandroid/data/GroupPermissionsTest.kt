package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群管理权限。这几条规则同时出现在成员长按菜单、群资料页入口、成员详情页三处，
 * 散着写必然分叉——分叉的表现是「按钮亮着但点了报 300204」或反过来，两种都很难自查。
 */
class GroupPermissionsTest {

    private fun info(
        myRole: String,
        permInvite: Boolean = false,
        permEditInfo: Boolean = false,
        muteAll: Long = 0,
        myMute: Long = 0,
    ) = GroupInfo(convId = "g1", myRole = myRole, permInvite = permInvite, permEditInfo = permEditInfo,
        muteUntil = muteAll, myMuteUntil = myMute)

    private fun member(uid: String, role: String) =
        GroupMember(userId = uid, role = role)

    private val owner = GroupMember.ROLE_OWNER
    private val admin = GroupMember.ROLE_ADMIN
    private val plain = GroupMember.ROLE_MEMBER

    @Test
    fun `等级序 owner 大于 admin 大于 member`() {
        assertTrue(GroupPermissions.rank(owner) > GroupPermissions.rank(admin))
        assertTrue(GroupPermissions.rank(admin) > GroupPermissions.rank(plain))
    }

    @Test
    fun `未知角色按最低处理——新服务端加角色时端上不误放行`() {
        assertEqualsRankZero("moderator")
        assertEqualsRankZero("")
    }
    private fun assertEqualsRankZero(role: String) {
        assertTrue("未知角色应为最低等级", GroupPermissions.rank(role) == 0)
        assertFalse("未知角色不该高于普通成员", GroupPermissions.outranks(role, plain))
    }

    @Test
    fun `严格高于——两个管理员互相踢不动`() {
        // 不「严格」的话，一个群里的管理员可以互相清场
        assertFalse(GroupPermissions.outranks(admin, admin))
        assertTrue(GroupPermissions.outranks(owner, admin))
        assertTrue(GroupPermissions.outranks(admin, plain))
        assertFalse(GroupPermissions.outranks(plain, plain))
        assertFalse(GroupPermissions.outranks(plain, admin))
    }

    @Test
    fun `踢人要管理层且严格高于对方，且不能踢自己`() {
        val i = info(admin)
        assertTrue(GroupPermissions.canRemove(i, member("u2", plain), myUid = "me"))
        assertFalse("管理员踢不动管理员", GroupPermissions.canRemove(i, member("u2", admin), "me"))
        assertFalse("踢不动群主", GroupPermissions.canRemove(i, member("u2", owner), "me"))
        assertFalse("不能踢自己——退群走另一条路", GroupPermissions.canRemove(i, member("me", plain), "me"))
        assertFalse("普通成员没有踢人权", GroupPermissions.canRemove(info(plain), member("u2", plain), "me"))
    }

    @Test
    fun `设管理员和转让群主仅群主可做`() {
        assertTrue(GroupPermissions.canSetRole(info(owner), member("u2", plain), "me"))
        assertFalse("管理员不能设管理员", GroupPermissions.canSetRole(info(admin), member("u2", plain), "me"))
        assertFalse("不能对自己操作", GroupPermissions.canSetRole(info(owner), member("me", plain), "me"))
        assertTrue(GroupPermissions.canTransfer(info(owner), member("u2", plain), "me"))
        assertFalse(GroupPermissions.canTransfer(info(admin), member("u2", plain), "me"))
    }

    @Test
    fun `群主不能直接退群——必须先转让`() {
        // 不挡的话用户点了退群只拿到一个错误码，而正确的引导是「先把群交给别人」
        assertFalse(GroupPermissions.canLeave(info(owner)))
        assertTrue(GroupPermissions.canLeave(info(admin)))
        assertTrue(GroupPermissions.canLeave(info(plain)))
    }

    @Test
    fun `公告只给管理层，不受「允许成员改群资料」开关影响`() {
        assertTrue(GroupPermissions.canEditAnnouncement(info(admin)))
        assertFalse(GroupPermissions.canEditAnnouncement(info(plain)))
        // 资料可以被开关放开，公告不行——公告比资料重
        assertTrue("关掉「仅管理员可改群资料」后全员可改", GroupPermissions.canEditInfo(info(plain, permEditInfo = false)))
        assertFalse(GroupPermissions.canEditAnnouncement(info(plain)))
    }

    @Test
    fun `禁言状态：0 没禁 负一永久 其余看是否到期`() {
        val now = 1_000_000L
        assertFalse(GroupPermissions.isMuteActive(0, now))
        assertTrue("−1 是永久", GroupPermissions.isMuteActive(-1, now))
        assertTrue(GroupPermissions.isMuteActive(now + 1000, now))
        assertFalse("已过期不算禁言", GroupPermissions.isMuteActive(now - 1000, now))
    }

    /**
     * **这两个开关的名字是「仅管理员可…」，不是「允许成员…」**——服务端语义见
     * `internal/group/group.go`（`perm_invite` 开启后仅群主/管理员可邀请；
     * `perm_edit_info` 开启后仅群主/管理员可改群资料），im-web 的
     * `canInviteHere = !gp?.perm_invite || canManage` 同口径。
     *
     * 本端此前把它读成了「允许成员…」，两条判据**都反了**，而且这个测试自己的名字
     * 与断言互相矛盾（名字说「不能邀」，断言写的是 assertTrue）——写错的语义被测试钉住，
     * 于是一直是绿的。2026-09-08 接群治理开关组 UI 时才看出来。
     */
    @Test
    fun `邀请：开了「仅管理员可邀请」后普通成员不能邀`() {
        assertFalse(GroupPermissions.canInvite(info(plain, permInvite = true)))
        assertTrue("没开这个开关时人人可邀", GroupPermissions.canInvite(info(plain, permInvite = false)))
        assertTrue("管理层恒可邀", GroupPermissions.canInvite(info(admin, permInvite = true)))
    }

    @Test
    fun `改群资料：开了「仅管理员可改」后普通成员不能改`() {
        assertFalse(GroupPermissions.canEditInfo(info(plain, permEditInfo = true)))
        assertTrue(GroupPermissions.canEditInfo(info(plain, permEditInfo = false)))
        assertTrue("管理层恒可改", GroupPermissions.canEditInfo(info(admin, permEditInfo = true)))
    }
}
