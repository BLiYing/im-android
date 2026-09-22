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
enum class GroupInfoPage { Pick, JoinRequests, Bans, Admins, MemberProfile, Media, Manage, Qr, Detail }

object GroupInfoNav {

    /**
     * 当前页。**顺序即层级**，由深到浅：
     * 选人页（从管理员页/管理页进，最深）> 待审申请 / 黑名单 / 管理员（都从管理页进）>
     * 成员资料（从详情进）> 媒体归档（从详情进）> 管理页 > 详情。
     */
    fun current(
        pickOpen: Boolean,
        joinRequestsOpen: Boolean,
        bansOpen: Boolean,
        adminsOpen: Boolean,
        memberProfileOpen: Boolean,
        /** 媒体**查看器**开着（不是归档页——2026-09-09 起归档是详情页里的内联页签，不再是独立一页）。 */
        mediaOpen: Boolean,
        managing: Boolean,
        /** 群二维码 / 群邀请链接页开着（同 `managing`，从详情页 Settings 区一行进）。 */
        qrOpen: Boolean = false,
    ): GroupInfoPage = when {
        pickOpen -> GroupInfoPage.Pick
        joinRequestsOpen -> GroupInfoPage.JoinRequests
        bansOpen -> GroupInfoPage.Bans
        adminsOpen -> GroupInfoPage.Admins
        memberProfileOpen -> GroupInfoPage.MemberProfile
        mediaOpen -> GroupInfoPage.Media
        managing -> GroupInfoPage.Manage
        qrOpen -> GroupInfoPage.Qr
        else -> GroupInfoPage.Detail
    }

    /**
     * 按下返回后应该回到哪一页（`null` = 已经在最外层，该退出整条链）。
     *
     * 写成「返回后的目标」而不是「关掉哪个 flag」，是为了让层级关系直接可读、也可测：
     * 待审申请退回**管理页**（不是详情页——它是从管理页点进去的）。
     */
    fun back(page: GroupInfoPage): GroupInfoPage? = when (page) {
        // 选人页可能从「管理员」页进（添加管理员），也可能从管理页直接进（转让群主/邀请入群）。
        // 退一层统一回管理页——**不做"记住从哪来"**：那需要一个真的返回栈，
        // 而这条链一共就几页，多退一层的代价远小于维护一个手写栈。
        GroupInfoPage.Pick -> GroupInfoPage.Manage
        GroupInfoPage.Bans -> GroupInfoPage.Manage
        GroupInfoPage.Admins -> GroupInfoPage.Manage
        GroupInfoPage.JoinRequests -> GroupInfoPage.Manage
        GroupInfoPage.MemberProfile -> GroupInfoPage.Detail
        GroupInfoPage.Media -> GroupInfoPage.Detail
        GroupInfoPage.Manage -> GroupInfoPage.Detail
        GroupInfoPage.Qr -> GroupInfoPage.Detail
        GroupInfoPage.Detail -> null
    }
}

/** 群这条链上「选人页是为了做什么」。三种用途共用一个选择页（见 `PickListScreen`）。 */
enum class PickPurpose { AddAdmin, Transfer, Invite, Call }
