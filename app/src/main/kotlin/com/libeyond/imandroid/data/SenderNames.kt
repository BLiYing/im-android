package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity

/**
 * 群聊气泡**发送者名字**取哪一份（2026-09-15，用户报：A 改了昵称，A 以前发的消息仍显示旧昵称，
 * 只有新消息是新昵称；新设备第一次登录进这个群则全是新昵称）。
 *
 * 根因：每条消息落库时带着一份 `from_nickname` **快照**（服务端下发那一刻现算：群昵称 > 昵称），
 * 本地库里的老消息不会被重写。取名若快照优先，老消息就永远是旧名。本端原本就是成员表在前，
 * 普通群不中招；但**好友名排在最前**（好友昵称压过群昵称，与另两端不同），而且超级群的成员表只有自己，
 * 非好友一律落到老快照。
 *
 * 链（与 iOS `IMGroupSenderName.h`、im-web `chatNaming.ts` 的 senderLabel 同序，SYMMETRY 已登记）：
 * 备注 > 成员表（群昵称 > 昵称）> 本窗该发送者最新一条的快照 > 本条快照 > 好友昵称。
 */
object SenderNames {

    /** 按上面的链取第一个非空的；全空返回 null（调用方决定画不画）。 */
    fun bubbleName(
        remark: String?,
        memberName: String?,
        latestSnapshot: String?,
        ownSnapshot: String?,
        friendNickname: String?,
    ): String? = listOf(remark, memberName, latestSnapshot, ownSnapshot, friendNickname)
        .firstOrNull { !it.isNullOrBlank() }

    /** 按显示序（旧 → 新）扫一遍，记下每个发送者**最新一条**带昵称快照的那份。 */
    fun latestNicknames(messages: List<MessageEntity>): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in messages) {
            val nick = m.fromNickname
            if (!nick.isNullOrBlank()) out[m.sender] = nick
        }
        return out
    }

    /**
     * 新到的群消息带的昵称与成员表对不上 ⇒ 对方在会话开着期间改了名，成员表是进会话时拉的旧份，该重拉。
     * 成员表查不到（超级群只有自己 / 还没拉到）或消息没带昵称时不算——重拉也拿不到更多。
     */
    fun memberNameStale(memberName: String?, inboundNickname: String?): Boolean =
        !memberName.isNullOrBlank() && !inboundNickname.isNullOrBlank() && memberName != inboundNickname
}
