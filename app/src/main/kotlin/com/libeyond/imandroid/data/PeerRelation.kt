package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry

/**
 * 单聊详情页对「这个人」的关系视图：好友态 / 拉黑 / 公开句柄**都从同一份好友行派生**。
 *
 * 为什么收口：详情页原先按钮与操作排读进页重拉过的 `friend`，而「备注名下面的用户名」却读启动时
 * 只拉一次的 `knownFriends`——两份数据新旧不一，于是启动之后才加的好友，按钮闪一下就对了，
 * 用户名却一直是空的（2026-10-10 用户报）。把三项绑在一个入参上，就不会再出现"读了两份"。
 */
data class PeerRelation(val isFriend: Boolean, val blocked: Boolean, val handle: String) {
    companion object {
        /** [friend] 为空 = 不是好友（或还不知道）：句柄也不显示。 */
        fun of(friend: FriendEntry?) = PeerRelation(
            isFriend = friend?.status == FriendEntry.ACCEPTED,
            blocked = friend?.blocked == true,
            handle = friend?.handle.orEmpty(),
        )
    }
}
