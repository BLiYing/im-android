package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 详情页「操作排」的可见项（对齐 iOS `IMChatDetailViewController+Header.m` 的 `actionPillSpecs`）。
 *
 * 这一排是 iOS 详情页头部最显眼的东西（大头像正下方一排圆角按钮），本端 2026-09-08 之前
 * 整排都不存在——群详情只有一行「聊天媒体」，单聊详情只有几张设置卡。
 *
 * **要对齐的是「谁看得见哪几个」这条判据，不是按钮长什么样**（本端用 Compose 画，
 * iOS 用 `IMGlassButtonConfiguration`，外观本来就不会一样）。判据逐条照抄：
 * - 系统通知会话：只有「更多」（加好友/消息/呼叫/视频/搜索都不适用）；
 * - 单聊非好友：只有「加好友」，**到此为止**——非好友发消息会被服务端 200103 拒收，
 *   摆一个必然失败的「消息」是把实现约束甩给用户；
 * - 单聊好友：消息（仅从外部入口进来时）/ 呼叫 / 视频 / 搜索 / 更多；
 * - 群聊：搜索 / 更多。
 */
enum class DetailAction { AddFriend, Message, Call, Video, GroupCall, Search, More }

/** 「更多」菜单项（对齐 iOS `moreTapped:`）。破坏性最重的排末位。 */
enum class DetailMoreAction {
    ShareContact, Block, Unblock, Report, ClearHistory, RemoveFriend, LeaveGroup, DissolveGroup,
}

object DetailActions {

    /**
     * 系统通知会话的对端 uid（服务端 `store.SystemUserID`，Telegram 777000 式）。
     * 三端各有一份同名常量——**它是协议常量不是本端选择**，别改。
     */
    const val SYSTEM_UID = "777000"

    fun isSystemPeer(uid: String): Boolean = uid == SYSTEM_UID

    /**
     * @param showsMessagePill 从**外部入口**（群成员行/通讯录/找人）进来时为 true——
     *   那些路径下这个单聊可能压根没打开过，需要一个「消息」入口把会话开出来。
     *   从聊天页自己点头像进来时为 false（已经在会话里了，再给一个「消息」是废按钮）。
     */
    fun pillsFor(
        isGroup: Boolean,
        isSystemPeer: Boolean,
        peerIsFriend: Boolean,
        showsMessagePill: Boolean,
        /** 群聊要不要出「语音 / 视频」pill（群通话接入后为 true）。单聊不看它。 */
        groupCallEnabled: Boolean = false,
    ): List<DetailAction> {
        if (!isGroup && isSystemPeer) return listOf(DetailAction.More)
        val out = mutableListOf<DetailAction>()
        if (!isGroup) {
            if (!peerIsFriend) return listOf(DetailAction.AddFriend)
            if (showsMessagePill) out += DetailAction.Message
            out += DetailAction.Call
            out += DetailAction.Video
        } else if (groupCallEnabled) {
            // 群里只有一个「群通话」入口（不分语音 / 视频）：以视频通话发起、摄像头默认关，
            // 通话中随时可开——语音通话里没有摄像头按钮，所以不能以语音发起。
            out += DetailAction.GroupCall
        }
        out += DetailAction.Search
        out += DetailAction.More
        return out
    }

    /** 「更多」里有哪几项。顺序即 iOS 的顺序（破坏性最重的在末位）。 */
    fun moreFor(
        isGroup: Boolean,
        isSystemPeer: Boolean,
        iAmOwner: Boolean,
        peerBlocked: Boolean,
        peerIsFriend: Boolean,
    ): List<DetailMoreAction> {
        if (isGroup) {
            val out = mutableListOf(DetailMoreAction.ClearHistory, DetailMoreAction.LeaveGroup)
            if (iAmOwner) out += DetailMoreAction.DissolveGroup
            return out
        }
        // 系统通知会话：拉黑/举报不适用（服务端护栏也会拒），只留清空。
        if (isSystemPeer) return listOf(DetailMoreAction.ClearHistory)
        val out = mutableListOf(
            DetailMoreAction.ShareContact,
            if (peerBlocked) DetailMoreAction.Unblock else DetailMoreAction.Block,
            DetailMoreAction.Report,
            DetailMoreAction.ClearHistory,
        )
        if (peerIsFriend) out += DetailMoreAction.RemoveFriend
        return out
    }

    fun label(a: DetailAction): String = when (a) {
        DetailAction.AddFriend -> Str.s(R.string.contacts_search_action_add)
        DetailAction.Message -> Str.s(R.string.chat_detail_pill_message)
        DetailAction.Call -> Str.s(R.string.chat_header_call)
        DetailAction.Video -> Str.s(R.string.common_video)
        DetailAction.GroupCall -> Str.s(R.string.chat_detail_pill_group_call)
        DetailAction.Search -> Str.s(R.string.common_search)
        DetailAction.More -> Str.s(R.string.common_more)
    }

    fun label(a: DetailMoreAction): String = when (a) {
        DetailMoreAction.ShareContact -> Str.s(R.string.chat_detail_recommend_to_friend)
        DetailMoreAction.Block -> Str.s(R.string.common_block)
        DetailMoreAction.Unblock -> Str.s(R.string.chat_menu_unblock)
        DetailMoreAction.Report -> Str.s(R.string.common_report)
        DetailMoreAction.ClearHistory -> Str.s(R.string.chat_detail_clear_history)
        DetailMoreAction.RemoveFriend -> Str.s(R.string.friend_menu_delete)
        DetailMoreAction.LeaveGroup -> Str.s(R.string.chat_detail_leave_group)
        DetailMoreAction.DissolveGroup -> Str.s(R.string.chat_detail_dissolve_group)
    }

    /** 红色项。**「取消拉黑」不是破坏性的**——它是在撤销一个破坏性动作（同 iOS `destructive:!peerBlocked`）。 */
    fun destructive(a: DetailMoreAction): Boolean = when (a) {
        DetailMoreAction.Block, DetailMoreAction.Report, DetailMoreAction.RemoveFriend,
        DetailMoreAction.LeaveGroup, DetailMoreAction.DissolveGroup -> true
        DetailMoreAction.Unblock, DetailMoreAction.ShareContact, DetailMoreAction.ClearHistory -> false
    }
}
