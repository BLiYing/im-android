package com.libeyond.imandroid.data

/**
 * 单聊详情这条链上「现在显示的是哪一页」。与 [GroupInfoPage] 同一套办法、同一个理由：
 * 返回键**一处按当前页派发**，枚举加一页 `when` 就编译不过，漏不掉。
 *
 * 详见 [GroupInfoPage] 的注释（2026-09-08 一天漏了三次的那笔账）。
 */
enum class ChatDetailPage { Media, Profile, Detail }

object ChatDetailNav {

    /**
     * 当前页。**顺序即层级**，由深到浅：媒体归档 > 用户资料 > 详情。
     *
     * 媒体查看器**不在这一层**：它归 `ConvMediaHost` 自己管（媒体归档 → 查看器是那一页内部的深度）。
     * 每个宿主只描述**自己这一层**的页面，层级才不会互相渗透。
     */
    fun current(mediaOpen: Boolean, profileOpen: Boolean): ChatDetailPage =
        when {
            mediaOpen -> ChatDetailPage.Media
            profileOpen -> ChatDetailPage.Profile
            else -> ChatDetailPage.Detail
        }

    /** 返回后回到哪一页（`null` = 已在最外层，该退出整条链）。 */
    fun back(page: ChatDetailPage): ChatDetailPage? = when (page) {
        ChatDetailPage.Media -> ChatDetailPage.Detail
        ChatDetailPage.Profile -> ChatDetailPage.Detail
        ChatDetailPage.Detail -> null
    }
}
