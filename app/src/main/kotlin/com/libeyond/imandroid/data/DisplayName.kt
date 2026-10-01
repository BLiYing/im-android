package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
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

    val UNNAMED: String get() = Str.s(R.string.common_unnamed_user)

    /** 群名为空时的回退占位。 */
    val UNNAMED_GROUP: String get() = Str.s(R.string.common_unnamed_group)

    /** 会话在**本机**列表/标题里显示的名字。可以带备注。 */
    fun ofConversation(c: ConversationSummary): String {
        if (c.remark.isNotBlank()) return c.remark
        return if (c.isGroup) {
            c.name.ifBlank { UNNAMED_GROUP }
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
        c.name.ifBlank { UNNAMED_GROUP }
    } else {
        c.peerNickname.ifBlank { UNNAMED }
    }

    /**
     * 头像回退用的首字母（2026-10-02 改，三端同口径，见 `../IMServer/docs/UI.md`「图标与头像资源」）：
     * 末一个字是汉字（CJK 统一表意文字及扩展）就取它（中文名去姓留名）；否则取首字母并转大写（英文名/用户名）。
     * 按「字形簇」取字（复用 [graphemeClusters]，同一套实现也给昵称省略号 [SenderRun.ellipsize] 用），
     * 不会把结尾的 emoji／组合字符切成半个乱码。空名返回空串（与 iOS `IMAvatarInitials` / Web `avatarInitial` 一致）。
     */
    fun initials(displayName: String): String {
        val t = displayName.trim()
        if (t.isEmpty()) return ""
        val clusters = graphemeClusters(t)
        val last = clusters.last()
        if (isHanCodePoint(last.codePointAt(0))) return last // 中文名：取末字
        return clusters.first().uppercase(java.util.Locale.ROOT) // 英文名/用户名：取首字母，大写
    }

    /// CJK 统一表意文字：基本区 + 兼容区 + 全部辅助平面扩展区（B 起，含 C/D/E/F/G…，该平面几乎全部留给 CJK 扩展）。
    private fun isHanCodePoint(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0xF900..0xFAFF) || (cp in 0x20000..0x3FFFD)

    /**
     * 好友在**本机界面**上的显示名：备注 > 昵称 > 用户名 > uid。
     * 备注是本机私有数据，**只能用于渲染**。
     */
    fun ofFriend(f: com.libeyond.imandroid.sdk.api.FriendEntry): String =
        f.remark.ifBlank { f.nickname }.ifBlank { f.username }.ifBlank { f.userId }

    /**
     * 好友的**公开名**：昵称 > 用户名 > uid，**绝不含备注**。
     *
     * 凡是「显示名会被写进要发出去的字节」的地方一律走这个（IMServer `docs/UI.md` 隐私红线）。
     * 个人名片就是这种地方：把「我给他起的外号」发给第三个人，是 iOS 与 im-web
     * **各出过一次**的线上事故。
     */
    fun publicNameOfFriend(f: com.libeyond.imandroid.sdk.api.FriendEntry): String =
        f.nickname.ifBlank { f.username }.ifBlank { f.userId }
}
