package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.ws.ConnState

/** 聊天页标题下那一行「该显示什么」。文案由 UI 层按资源渲染，这里只决定**选哪一种**。 */
sealed interface ChatSubtitleSpec {
    data object None : ChatSubtitleSpec
    /** 单聊对端在输入（只有一人，不带名字）。 */
    data object Typing : ChatSubtitleSpec
    /** 群里有人在输入：「{昵称} 正在输入」，[uid] 由 UI 层解析成显示名。 */
    data class TypingNamed(val uid: String) : ChatSubtitleSpec
    data object Connecting : ChatSubtitleSpec
    data object Disconnected : ChatSubtitleSpec
    /** 单聊在线态，[text] 已是渲染好的文案（「在线」「3 分钟前」…）。 */
    data class PeerPresence(val text: String) : ChatSubtitleSpec
    data class Members(val count: Int, val isSuper: Boolean) : ChatSubtitleSpec
    /** 超级群但人数未知。 */
    data object SuperOnly : ChatSubtitleSpec
}

/**
 * 聊天页副标题 / 标题的选择规则，**逐条对齐 iOS** `IMChatViewController+Socket.m` 的
 * `im_navigationSubtitle` 与 `updateTitle`。
 *
 * 优先级：正在输入 → 连接状态 → 单聊在线态 / 群成员数。连接状态排在在线态与成员数之前：
 * 断线时本地在线快照无法再更新，显示连接态才是可验证的状态。多选态由调用方不画副标题。
 */
object ChatSubtitle {
    fun resolve(
        isGroup: Boolean,
        typingUid: String?,
        conn: ConnState,
        peerPresence: String,
        memberCount: Int,
        loadedMembers: Int,
        isSuper: Boolean,
    ): ChatSubtitleSpec {
        if (typingUid != null) return if (isGroup) ChatSubtitleSpec.TypingNamed(typingUid) else ChatSubtitleSpec.Typing
        when (conn) {
            ConnState.Connecting -> return ChatSubtitleSpec.Connecting
            ConnState.Idle -> return ChatSubtitleSpec.Disconnected
            ConnState.Connected -> Unit
        }
        if (!isGroup) return if (peerPresence.isEmpty()) ChatSubtitleSpec.None else ChatSubtitleSpec.PeerPresence(peerPresence)
        // 人数优先取 memberCount：超级群的成员表只含我自己，用 loadedMembers 会把「2 万位成员」显示成「1 位成员」
        val count = if (memberCount > 0) memberCount else loadedMembers
        return when {
            count > 0 -> ChatSubtitleSpec.Members(count, isSuper)
            isSuper -> ChatSubtitleSpec.SuperOnly
            else -> ChatSubtitleSpec.None
        }
    }

    /** 群备注（仅本人可见、多端同步）非空即替代群名作标题；单聊不用它（单聊走好友备注）。 */
    fun title(base: String, isGroup: Boolean, groupRemark: String): String =
        if (isGroup && groupRemark.isNotBlank()) groupRemark else base
}
