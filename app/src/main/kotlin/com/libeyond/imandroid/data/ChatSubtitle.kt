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

    /**
     * 聊天页标题。对齐 iOS `updateTitle`：群备注（仅本人可见、多端同步）非空 > 真实群名。单聊不用它（单聊走好友备注）。
     *
     * [snapshotTitle] 是进页时的会话快照标题（`DisplayName.ofConversation`，**已含进页那一刻的备注**），
     * 页面开着时它不会跟着变，所以：
     * - [groupRemark] 为 null（还没拉回 / 拉取失败）→ 用快照，离线时也不丢备注；
     * - 非空白 → 用备注（实时值）；
     * - 空白（备注被清除）→ 用 [realGroupName]，**不能回退快照**——快照里可能还是已被清掉的旧备注。
     *   调用方保证群资料已拉回时 [realGroupName] 非空（空群名已换成「未命名群」）；仅群资料**还没拉回**（null）时才无从得知，
     *   回退快照——这是已知的窄窗口（进页后群资料几百毫秒内就位，远早于有人清备注）。
     */
    fun title(snapshotTitle: String, isGroup: Boolean, groupRemark: String?, realGroupName: String?): String = when {
        !isGroup || groupRemark == null -> snapshotTitle
        groupRemark.isNotBlank() -> groupRemark
        else -> realGroupName?.takeIf { it.isNotBlank() } ?: snapshotTitle
    }
}
