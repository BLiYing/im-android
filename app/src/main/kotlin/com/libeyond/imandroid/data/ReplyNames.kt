package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 引用相关的两处**名字**：气泡里引用块的被引用者、输入栏回复条的「回复 X」。
 *
 * 两处都**绝不退到 uid**：uid 是 10 位内部 ID，本端此前在引用块里原样显示过它
 * （本地没有备注时 `localNameOf(uid) ?: uid`）。
 */
object ReplyNames {

    /**
     * 气泡引用块里被引用者的名字（只有群聊才画这一行）。
     *
     * 自己 →「你」；否则 本地名（备注）> 群成员名 > 原消息上带的昵称；都没有 → null，整行不画。
     */
    fun quoteFrom(
        uid: String?,
        myUid: String,
        localName: String?,
        memberName: String?,
        originalNickname: String?,
    ): String? {
        if (uid.isNullOrBlank()) return null
        if (uid == myUid) return Str.s(R.string.common_you)
        return listOf(localName, memberName, originalNickname).firstOrNull { !it.isNullOrBlank() }
    }

    /**
     * 输入栏回复条的标题。
     *
     * 引自己 →「回复 自己」；群聊按 本地名 > 群成员名 > 消息上的昵称；
     * 单聊只有两个人，被引用的不是自己就是对方，直接用会话标题（它已经是备注优先的显示名）。
     */
    fun replyBarTitle(
        sender: String,
        myUid: String,
        isGroup: Boolean,
        localName: String?,
        memberName: String?,
        fromNickname: String?,
        convTitle: String,
    ): String {
        val name = when {
            sender == myUid -> Str.s(R.string.chat_reply_self)
            isGroup -> listOf(localName, memberName, fromNickname).firstOrNull { !it.isNullOrBlank() }.orEmpty()
            else -> convTitle
        }
        return if (name.isBlank()) Str.s(R.string.chat_reply_title_fallback) else Str.s(R.string.chat_reply_who, name)
    }
}
