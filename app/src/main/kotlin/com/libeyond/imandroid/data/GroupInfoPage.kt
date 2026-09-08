package com.libeyond.imandroid.data

/**
 * 群详情这条链上「现在显示的是哪一页」。
 *
 * **为什么要有它**：`GroupInfoHost` 用整页替换做导航，页面是一个个加上去的
 * （详情 → 成员资料 → 待审申请 → 群管理），而每加一个，返回键都得有人接。
 * 2026-09-08 一天之内漏了三次——媒体查看器、待审申请、群管理，
 * 每次的表现都一样：**按返回直接退出整个 App**。
 *
 * 修法不是再补一个 `BackHandler`（那只会有第四次），而是把返回收成
 * **一个按当前页派发的 `when`**：枚举加一个值，`when` 就编译不过，漏不掉。
 * 同 `ChatOverlays.topmost` 的思路。
 */
enum class GroupInfoPage { JoinRequests, MemberProfile, Media, Manage, Detail }

object GroupInfoNav {

    /**
     * 当前页。**顺序即层级**，由深到浅：
     * 待审申请（从管理页进）> 成员资料（从详情进）> 媒体归档（从详情进）> 管理页 > 详情。
     */
    fun current(
        joinRequestsOpen: Boolean,
        memberProfileOpen: Boolean,
        mediaOpen: Boolean,
        managing: Boolean,
    ): GroupInfoPage = when {
        joinRequestsOpen -> GroupInfoPage.JoinRequests
        memberProfileOpen -> GroupInfoPage.MemberProfile
        mediaOpen -> GroupInfoPage.Media
        managing -> GroupInfoPage.Manage
        else -> GroupInfoPage.Detail
    }

    /**
     * 按下返回后应该回到哪一页（`null` = 已经在最外层，该退出整条链）。
     *
     * 写成「返回后的目标」而不是「关掉哪个 flag」，是为了让层级关系直接可读、也可测：
     * 待审申请退回**管理页**（不是详情页——它是从管理页点进去的）。
     */
    fun back(page: GroupInfoPage): GroupInfoPage? = when (page) {
        GroupInfoPage.JoinRequests -> GroupInfoPage.Manage
        GroupInfoPage.MemberProfile -> GroupInfoPage.Detail
        GroupInfoPage.Media -> GroupInfoPage.Detail
        GroupInfoPage.Manage -> GroupInfoPage.Detail
        GroupInfoPage.Detail -> null
    }
}
