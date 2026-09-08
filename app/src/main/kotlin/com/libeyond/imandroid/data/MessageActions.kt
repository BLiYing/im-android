package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType

/** 消息长按菜单里的一项。 */
enum class MessageAction(val label: String, val destructive: Boolean = false) {
    Copy("复制"),
    Reply("引用"),
    Forward("转发"),
    Recall("撤回", destructive = true),
    /** 为所有人删除。 */
    DeleteForEveryone("为所有人删除", destructive = true),
    /** 仅删除自己（走 REST /messages/hide）。 */
    HideForMe("仅删除自己", destructive = true),
}

/**
 * 消息长按菜单的可用项（CHAT_UX §13/§14 的矩阵）。
 *
 * 抽成纯函数是因为**这张矩阵有一堆互相牵扯的条件**，散在 UI 里必然漂移——
 * iOS/Web 都为此专门收敛过一次（Web 的 `menus.ts` + 单测）。
 */
object MessageActions {

    /** 撤回时间窗，服务端权威（默认 2 分钟）。端上先挡一道，避免明知会失败还发。 */
    const val RECALL_WINDOW_MS = 2 * 60 * 1000L

    /**
     * @param msg 目标消息
     * @param myUid 我
     * @param isGroup 群聊
     * @param iAmManager 我是群主或管理员
     * @param now 当前毫秒
     */
    fun availableFor(
        msg: MessageEntity,
        myUid: String,
        isGroup: Boolean,
        iAmManager: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<MessageAction> {
        // 撤回墓碑上什么都不给——正文已被服务端脱敏，复制/引用都没有意义
        if (msg.recalledAt != null && msg.recalledAt > 0) return emptyList()
        // 待确认的消息（还没 conv_seq）不能做任何服务端操作
        if (msg.convSeq <= 0) return emptyList()

        val mine = msg.sender == myUid
        val out = mutableListOf<MessageAction>()

        // 复制**只给文本**：给图片/文件一个"复制"却什么都没进剪贴板，比没有更糟
        if (msg.contentType == ContentType.TEXT && msg.content.isNotEmpty()) out += MessageAction.Copy

        // 系统消息不可引用（它没有发送者，引用条显示不出来源）
        if (msg.contentType != ContentType.SYSTEM) out += MessageAction.Reply

        // 转发 / 多选：条件与 Forward.canForward 同源——**别在这里重写一遍判据**，
        // 两处判据分叉会让菜单里有「转发」但点了没反应（或反过来）。
        if (Forward.canForward(msg)) out += MessageAction.Forward

        // 撤回：仅本人，且在时间窗内。服务端超窗回 300008
        if (mine && now - msg.timestamp <= RECALL_WINDOW_MS) out += MessageAction.Recall

        // 为所有人删除：发送者本人恒可；群聊中群主/管理员亦可删他人。**无时间窗**
        if (mine || (isGroup && iAmManager)) out += MessageAction.DeleteForEveryone

        // 仅删除自己：任何消息都可以
        out += MessageAction.HideForMe

        return out
    }
}

/** 会话长按菜单（CHAT_UX §12/§14）。 */
enum class ConversationAction(val label: String, val destructive: Boolean = false) {
    Pin("置顶"),
    Unpin("取消置顶"),
    Mute("免打扰"),
    Unmute("取消免打扰"),
    MarkUnread("标为未读"),
    // 文案逐字对齐 iOS `conversationActionsFor:`（「设为已读」不是「标为已读」、「删除」不是「删除会话」）
    MarkRead("设为已读"),
    Delete("删除", destructive = true),
}

object ConversationActions {
    fun availableFor(pinnedAt: Long, muted: Boolean, markedUnread: Boolean, unread: Int): List<ConversationAction> =
        buildList {
            add(if (pinnedAt > 0) ConversationAction.Unpin else ConversationAction.Pin)
            add(if (muted) ConversationAction.Unmute else ConversationAction.Mute)
            // 有未读时给「标为已读」，没未读时给「标为未读」——反过来给等于给一个无操作项
            if (unread > 0 || markedUnread) add(ConversationAction.MarkRead)
            else add(ConversationAction.MarkUnread)
            add(ConversationAction.Delete)
        }
}
