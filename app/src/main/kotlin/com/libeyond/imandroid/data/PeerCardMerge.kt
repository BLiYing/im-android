package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.UserCard

/**
 * 进入单聊信息页时拉到的**对端权威名片**，怎么并回本机会话行（与 IMProgram `loadPeerProfile`、im-web `loadPeerCard` 同口径）。
 *
 * 会话列表里那份对端昵称 / 头像是上次刷新列表时的快照：对方改了头像或昵称，列表要等下一次刷新才知道。
 * 名片是权威值，拿到就并回去——**信息页头部、会话列表、通话界面读的都是这一行**，所以三处天然一致。
 *
 * - 名字：备注 > 昵称；名片里两者都空就保持原样（fail-open，不把一个能用的名字清成「未命名用户」）
 * - 头像：名片给了才覆盖（空 = 保持原样，与 IMProgram 一致）
 * - 备注：服务端备注是权威值，原样并回（空串 = 已清除）
 */
object PeerCardMerge {

    data class Merged(val title: String, val avatarUrl: String, val peerRemark: String)

    fun merge(row: ConversationEntity, card: UserCard): Merged = Merged(
        title = card.remark.ifBlank { card.nickname }.ifBlank { row.title },
        avatarUrl = card.avatarUrl.ifBlank { row.avatarUrl },
        peerRemark = card.remark,
    )
}
