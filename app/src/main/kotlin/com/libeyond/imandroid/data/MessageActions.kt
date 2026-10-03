package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ContentType

/** 消息长按菜单里的一项。`label` 惰性取值（[Str.s]），切语言不需要重建这些枚举实例。 */
enum class MessageAction(@StringRes private val labelRes: Int, val destructive: Boolean = false) {
    Copy(R.string.common_copy),
    Reply(R.string.chat_msg_menu_reply),
    Forward(R.string.common_forward),
    /** 收藏（M4-4）：内容快照存到服务端，原消息撤回/删除后仍在。 */
    Favorite(R.string.common_favorite),
    /** 语音转文字（服务端识别，见 `voice/VoiceTranscriber.kt`）。仅对语音消息出现。 */
    Transcribe(R.string.chat_msg_menu_transcribe),
    /** 转写面板已展开时同一位置换成这项——只收本地面板，不删服务端结果（会话内共享）。 */
    TranscribeOff(R.string.chat_msg_menu_transcribe_cancel),
    /** 进入多选态（判据在 [ChatSelection]）。 */
    MultiSelect(R.string.chat_msg_menu_multi_select),
    /** 编辑（本人文本消息）：进编辑态，发 msg_op edit。 */
    Edit(R.string.common_edit),
    /** 翻译（文本消息）：译文挂在气泡内、只在内存。 */
    Translate(R.string.chat_msg_menu_translate),
    /** 举报这条消息（别人的消息）。 */
    Report(R.string.common_report),
    /** 置顶 / 取消置顶（判据 [PinnedBanner.pinAction]）：不做本地乐观更新，等服务端广播回来再变。 */
    Pin(R.string.chat_msg_menu_pin),
    Unpin(R.string.chat_msg_menu_unpin),
    Recall(R.string.chat_msg_menu_recall, destructive = true),
    /** 为所有人删除。 */
    DeleteForEveryone(R.string.delete_sheet_everyone, destructive = true),
    /** 仅删除自己（走 REST /messages/hide）。 */
    HideForMe(R.string.delete_sheet_only_me, destructive = true),
    ;

    val label: String get() = Str.s(labelRes)
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
        /**
         * 这条语音的转写面板**当前**是不是展开着（[com.libeyond.imandroid.voice.VoiceTranscriber.isExpanded]）。
         * 决定语音消息这一项显「转文字」还是「取消转文字」；非语音消息忽略此参数。
         */
        hasTranscript: Boolean = false,
        /** 我能不能置顶（[PinnedBanner.canPin]）；放在最后以保持老调用点的位置参数不变。 */
        canPin: Boolean = false,
    ): List<MessageAction> {
        val pin = PinnedBanner.pinAction(msg, canPin)
        // 撤回墓碑上什么都不给——正文已被服务端脱敏，复制/引用都没有意义。
        // **唯一例外：它若还挂着置顶，要能取消**（否则撤回后的陈旧置顶没法清，横幅永远指着一行墓碑）
        if (msg.recalledAt != null && msg.recalledAt > 0) return listOfNotNull(pin.takeIf { it == MessageAction.Unpin })
        // 待确认的消息（还没 conv_seq）不能做任何服务端操作
        if (msg.convSeq <= 0) return emptyList()

        // 通话记录是系统事实不是「说过的话」：长按只有「仅删除自己」（无复制 / 引用 / 转发 / 收藏 / 撤回 / 多选）
        if (msg.contentType == ContentType.CALL) return listOf(MessageAction.HideForMe)

        val mine = msg.sender == myUid
        val out = mutableListOf<MessageAction>()

        // 语音转文字：放在最前（对齐 iOS/Web 长按菜单顶部靠前的位置）。只对语音消息出现，
        // 已展开 → 换成「取消转文字」，同一个位置不占两行。
        if (msg.contentType == ContentType.VOICE) {
            out += if (hasTranscript) MessageAction.TranscribeOff else MessageAction.Transcribe
        }

        // 复制什么、给不给复制，都归 [copyKindOf]（对齐 iOS `copyMessageToPasteboard:` 那张矩阵）
        if (copyKindOf(msg) != null) out += MessageAction.Copy

        // 系统消息不可引用（它没有发送者，引用条显示不出来源）
        if (msg.contentType != ContentType.SYSTEM) out += MessageAction.Reply

        // 转发 / 多选：条件与 Forward.canForward 同源——**别在这里重写一遍判据**，
        // 两处判据分叉会让菜单里有「转发」但点了没反应（或反过来）。
        if (Forward.canForward(msg)) out += MessageAction.Forward

        // 收藏：**文本/图片/视频/文件/语音/链接/名片/聊天记录都给**（快照存 content + content_type，后端通用），
        // 顺序照 iOS `messageActionsForMessage:` 排在转发之后。判据与多选底栏「收藏」同一份
        // [SelectionActions.favoritable]——两个入口各判一遍的话，迟早出现"长按能收藏、多选里收不了"。
        // 此前本端长按菜单没有这一项（2026-09-17 用户报），只能先进多选再收藏。
        if (SelectionActions.favoritable(listOf(msg)).isNotEmpty()) out += MessageAction.Favorite

        // 多选：判据比转发**宽一档**（走 ChatSelection.selectable），因为进多选后还能勾上
        // 空内容/已删除那些"能勾但转不出去"的条目——它们由发送前的复核滤掉并如实提示。
        // 从一条转不出去的消息进多选是合理的（用户可能想批量删它们）。
        if (ChatSelection.selectable(msg)) out += MessageAction.MultiSelect

        // 撤回：仅本人，且在时间窗内。服务端超窗回 300008
        if (mine && now - msg.timestamp <= RECALL_WINDOW_MS) out += MessageAction.Recall

        // 置顶 / 取消置顶：排在撤回之后（对齐 iOS 菜单顺序：… 撤回 · 置顶 · 编辑 · 多选 · 翻译 · 举报 · 删除）
        pin?.let { out += it }
        // 编辑：仅本人文本消息，无时间窗（服务端同判：非本人 300006、非文本/已撤回 300007）。
        // convSeq>0 必须——待确认行走编辑会落到「发新消息」分支（iOS 注释）
        if (mine && msg.contentType == ContentType.TEXT && msg.content.isNotBlank()) out += MessageAction.Edit

        // 为所有人删除：发送者本人恒可；群聊中群主/管理员亦可删他人。**无时间窗**
        if (mine || (isGroup && iAmManager)) out += MessageAction.DeleteForEveryone

        // 翻译：文本消息（含纯链接）；目标语言恒 zh
        if (msg.contentType == ContentType.TEXT && msg.content.isNotBlank()) out += MessageAction.Translate
        // 举报：别人的消息（系统消息/系统账号不可举报）。用户级举报留在资料页，不并进来
        if (SelectionActions.reportableSender(listOf(msg), myUid) != null && msg.contentType != ContentType.SYSTEM &&
            !DetailActions.isSystemPeer(msg.sender)
        ) out += MessageAction.Report

        // 仅删除自己：任何消息都可以
        out += MessageAction.HideForMe

        return out
    }
}

/**
 * 「复制」这一下到底往剪贴板里放什么（对齐 iOS `copyMessageToPasteboard:`）。
 *
 * iOS 那段的顺序是硬约定，**caption 压过一切**：带图说的消息，「复制」作用在**文本**上
 * （"这类消息的文本操作作用于文本"），而不是把图复制走。
 */
enum class CopyKind {
    /** 图说文本（消息带 caption 时，无论它是图还是文件）。 */
    Caption,

    /** 图片字节本身。安卓没有位图剪贴板，实际放的是 FileProvider 的 `content://`，见 `ui/CopyImageAction.kt`。 */
    Image,

    /** 媒体的绝对链接（video / file）。 */
    Link,

    /** 纯文本正文。 */
    Text,
}

/**
 * 这条消息「复制」什么；`null` = 不给「复制」这一项。
 *
 * 本端此前**只给 TEXT**，理由写的是"给图片一个复制却什么都没进剪贴板，比没有更糟"——
 * 那在没有复制图片能力时是对的，2026-09-16 补了图片复制之后就该放开了（用户报的对齐项）。
 *
 * **两处刻意不跟 iOS**：
 * ① **voice 不给**——iOS 走的是最后那条兜底分支，复制的是 `message.content`，
 *    也就是一段形如 `/uploads/x.m4a` 的相对路径，对用户没有任何意义；
 * ② **系统消息不给**——它不是用户写的话，本端连「引用」都没给它。
 */
fun copyKindOf(msg: MessageEntity): CopyKind? = when {
    !msg.caption.isNullOrBlank() -> CopyKind.Caption
    msg.contentType == ContentType.IMAGE && msg.content.isNotBlank() -> CopyKind.Image
    (msg.contentType == ContentType.VIDEO || msg.contentType == ContentType.FILE) &&
        msg.content.isNotBlank() -> CopyKind.Link
    msg.contentType == ContentType.TEXT && msg.content.isNotEmpty() -> CopyKind.Text
    else -> null
}

/** 会话长按菜单（CHAT_UX §12/§14）。`label` 惰性取值（[Str.s]），切语言不需要重建这些枚举实例。 */
enum class ConversationAction(@StringRes private val labelRes: Int, val destructive: Boolean = false) {
    Pin(R.string.conv_menu_pin),
    Unpin(R.string.conv_menu_unpin),
    Mute(R.string.conv_menu_mute),
    Unmute(R.string.conv_menu_unmute),
    MarkUnread(R.string.conv_menu_mark_unread),
    // 文案逐字对齐 iOS `conversationActionsFor:`（「设为已读」不是「标为已读」、「删除」不是「删除会话」）
    MarkRead(R.string.conv_menu_mark_read),
    Delete(R.string.common_delete, destructive = true),
    ;

    val label: String get() = Str.s(labelRes)
}

object ConversationActions {
    /** @param muted **有效**免打扰状态（调用方先用 `MuteState.isMutedNow` 算过一遍，不是原始存储值）——
     *  否则一个刚过期的定时免打扰会话仍会显示「取消免打扰」而不是「免打扰」。 */
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
