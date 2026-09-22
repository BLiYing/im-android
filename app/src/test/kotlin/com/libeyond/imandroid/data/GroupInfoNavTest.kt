package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 群详情这条链的返回键。**这组测试存在的理由是同一个 bug 一天犯了三次**：
 * 每加一个整页替换的子页面，就忘了给返回键留出路，表现是「按返回直接退出整个 App」。
 */
class GroupInfoNavTest {

    @Test
    fun `层级由深到浅：选人 大于 待审 大于 成员资料 大于 管理页 大于 详情`() {
        assertEquals(
            GroupInfoPage.Pick,
            GroupInfoNav.current(pickOpen = true, joinRequestsOpen = true, bansOpen = true,
                adminsOpen = true, memberProfileOpen = true, mediaOpen = true, managing = true),
        )
        assertEquals(
            GroupInfoPage.JoinRequests,
            GroupInfoNav.current(pickOpen = false, joinRequestsOpen = true, bansOpen = true, adminsOpen = true, memberProfileOpen = true, mediaOpen = true, managing = true),
        )
        assertEquals(
            GroupInfoPage.MemberProfile,
            GroupInfoNav.current(pickOpen = false, joinRequestsOpen = false, bansOpen = false, adminsOpen = false, memberProfileOpen = true, mediaOpen = true, managing = true),
        )
        assertEquals(
            GroupInfoPage.Media,
            GroupInfoNav.current(pickOpen = false, joinRequestsOpen = false, bansOpen = false, adminsOpen = false, memberProfileOpen = false, mediaOpen = true, managing = true),
        )
        assertEquals(
            GroupInfoPage.Manage,
            GroupInfoNav.current(pickOpen = false, joinRequestsOpen = false, bansOpen = false, adminsOpen = false, memberProfileOpen = false, mediaOpen = false, managing = true),
        )
        assertEquals(
            GroupInfoPage.Detail,
            GroupInfoNav.current(pickOpen = false, joinRequestsOpen = false, bansOpen = false, adminsOpen = false, memberProfileOpen = false, mediaOpen = false, managing = false),
        )
    }

    /**
     * 群二维码/邀请链接页排在「管理页」之后、「详情」之前——`managing` 与 `qrOpen`
     * 结构上互斥（渲染层同一时刻只出一个整页替换分支），但 `current()` 的优先级
     * 仍要显式钉住：`managing=true` 时哪怕 `qrOpen` 也是 true，也该出 `Manage`。
     */
    @Test
    fun `二维码页排在管理页之后、详情之前`() {
        assertEquals(
            GroupInfoPage.Qr,
            GroupInfoNav.current(pickOpen = false, joinRequestsOpen = false, bansOpen = false, adminsOpen = false, memberProfileOpen = false, mediaOpen = false, managing = false, qrOpen = true),
        )
        assertEquals(
            GroupInfoPage.Manage,
            GroupInfoNav.current(pickOpen = false, joinRequestsOpen = false, bansOpen = false, adminsOpen = false, memberProfileOpen = false, mediaOpen = false, managing = true, qrOpen = true),
        )
    }

    /**
     * **待审申请退回的是管理页，不是详情页**——它是从管理页点进去的。
     * 退错一层的表现很隐蔽：用户按返回，界面确实变了，但少了一层，
     * 再按一次就退出了整条链，像是"返回键有时候要按两下有时候一下"。
     */
    @Test
    fun `从管理页进的几页都退回管理页`() {
        assertEquals(GroupInfoPage.Manage, GroupInfoNav.back(GroupInfoPage.JoinRequests))
        assertEquals(GroupInfoPage.Manage, GroupInfoNav.back(GroupInfoPage.Bans))
        assertEquals(GroupInfoPage.Manage, GroupInfoNav.back(GroupInfoPage.Admins))
        assertEquals(GroupInfoPage.Manage, GroupInfoNav.back(GroupInfoPage.Pick))
    }

    @Test
    fun `成员资料、媒体归档、管理页都退回详情`() {
        assertEquals(GroupInfoPage.Detail, GroupInfoNav.back(GroupInfoPage.MemberProfile))
        assertEquals(GroupInfoPage.Detail, GroupInfoNav.back(GroupInfoPage.Media))
        assertEquals(GroupInfoPage.Detail, GroupInfoNav.back(GroupInfoPage.Manage))
        assertEquals(GroupInfoPage.Detail, GroupInfoNav.back(GroupInfoPage.Qr))
    }

    /** 详情页是这条链的最外层，再返回就该整条退出（交回调用方）。 */
    @Test
    fun `详情页返回是离开整条链`() {
        assertNull(GroupInfoNav.back(GroupInfoPage.Detail))
    }

    /**
     * **每一页都要有明确的返回目标**（详情除外）。
     * 新加一页却忘了在 [GroupInfoNav.back] 里给出路，这条会红。
     */
    @Test
    fun `每一页的返回目标都有定义`() {
        for (p in GroupInfoPage.entries) {
            if (p == GroupInfoPage.Detail) continue
            assertEquals("$p 没有返回目标 → 按返回会直接退出整个 App", true, GroupInfoNav.back(p) != null)
        }
    }
}
