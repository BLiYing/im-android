package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 隐私红线（IMServer `docs/UI.md`）：**备注只能出现在本机渲染**，
 * 凡是「显示名会被写进要发出去的字节」的地方一律走公开名。
 * 个人名片正是这种地方——iOS 与 im-web **各出过一次**把备注发给第三方的事故。
 */
class FriendNameTest {

    private fun f(remark: String = "", nick: String = "", user: String = "", uid: String = "u1") =
        FriendEntry(userId = uid, username = user, nickname = nick, remark = remark)

    @Test
    fun `界面显示备注优先`() {
        assertEquals("老王", DisplayName.ofFriend(f(remark = "老王", nick = "王小明")))
        assertEquals("王小明", DisplayName.ofFriend(f(nick = "王小明", user = "wxm")))
        assertEquals("wxm", DisplayName.ofFriend(f(user = "wxm")))
        assertEquals("u1", DisplayName.ofFriend(f()))
    }

    @Test
    fun `公开名绝不含备注`() {
        // 这一条错了就是把「我给他起的外号」发给第三个人
        assertEquals("王小明", DisplayName.publicNameOfFriend(f(remark = "老王", nick = "王小明")))
        assertEquals("wxm", DisplayName.publicNameOfFriend(f(remark = "老王", user = "wxm")))
        // 末级兜底是 uid，**不是备注**
        assertEquals("u1", DisplayName.publicNameOfFriend(f(remark = "老王")))
    }
}
