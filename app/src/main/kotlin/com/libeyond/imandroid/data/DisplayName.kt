package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ConversationSummary

/**
 * 显示名解析——**三端统一的唯一回退链**（`../IMServer/docs/UI.md`「用户标识」节）。
 *
 * ## 末级不是 uid
 * 账号体系重构后 `user_id` 是服务端分配的 10 位随机数字**内部 ID**，
 * 露在界面上对用户毫无意义。回退链末级是「未命名用户」，**不是** uid。
 *
 * ## 优先级
 * - 会话标题：会话备注(remark) > 好友备注(peerRemark) > 昵称 > 「未命名用户」
 * - 群：会话备注 > 真实群名
 *
 * ## 隐私红线（改这块必核）
 * 备注**只能出现在本机渲染里**，绝不能进入任何会发给别人的内容。
 * 凡是「显示名会被写进要发出去的字节」的地方（合并转发的标题与条目名、@提及插入的 token），
 * 一律走 [publicName]。iOS 2026-08-29 真出过 P0：把和「老王」的聊天记录转发给别人，
 * 对方卡片标题就是「老王 的聊天记录」。
 */
object DisplayName {

    const val UNNAMED = "未命名用户"

    /** 会话在**本机**列表/标题里显示的名字。可以带备注。 */
    fun ofConversation(c: ConversationSummary): String {
        if (c.remark.isNotBlank()) return c.remark
        return if (c.isGroup) {
            c.name.ifBlank { "未命名群聊" }
        } else {
            c.peerRemark.ifBlank { c.peerNickname }.ifBlank { UNNAMED }
        }
    }

    /**
     * **对外可见名**——会被写进发出去的字节时用这个。
     * 群=真实群名，单聊=对端昵称。**绝不含任何备注**。
     *
     * 判据不是"这个名字私不私密"，而是**"换个人看还成不成立"**：
     * 「我」也不是私房名，但烧进 JSON 发出去后收件人看到的是一排「我」
     * （iOS 2026-08-30→09-05 的变体事故）。
     */
    fun publicName(c: ConversationSummary): String = if (c.isGroup) {
        c.name.ifBlank { "未命名群聊" }
    } else {
        c.peerNickname.ifBlank { UNNAMED }
    }

    /** 头像回退用的首字母：解析出的显示名**末两位**（三端同口径）。 */
    fun initials(displayName: String): String {
        val t = displayName.trim()
        if (t.isEmpty()) return "?"
        return if (t.length <= 2) t else t.takeLast(2)
    }
}
