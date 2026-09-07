package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 群成员 → 个人资料页的两步推导。每条都对应一种界面上看得见的错。 */
class MemberProfileTest {

    private val me = "1001"

    private val member = GroupMember(
        userId = "1002",
        username = "libeyond",
        nickname = "用户1002",
        groupNickname = "群里叫我小明",
        avatarUrl = "/avatars/1002.jpg",
        role = "member",
    )

    private val friend = FriendEntry(
        userId = "1002",
        username = "libeyond",
        nickname = "用户1002",
        remark = "张曼玉1002-朝辞白帝彩云间",
        status = FriendEntry.ACCEPTED,
    )

    // —— 关系 ——

    /** 不判自己的话，点到自己头上会看到一个「加好友」按钮。 */
    @Test
    fun `看自己是单独一档`() {
        assertEquals(MemberProfile.RELATION_SELF, MemberProfile.relationOf(me, me, null))
    }

    @Test
    fun `好友按本地表的 status 定型`() {
        assertEquals(FriendEntry.ACCEPTED, MemberProfile.relationOf("1002", me, friend))
        assertEquals(
            FriendEntry.PENDING,
            MemberProfile.relationOf("1002", me, friend.copy(status = FriendEntry.PENDING)),
        )
    }

    @Test
    fun `不在好友表里就是陌生人`() {
        assertEquals("", MemberProfile.relationOf("1002", me, null))
    }

    /**
     * 未登录 / uid 拿不到时 `myUid` 是空串。这时**不能**把空 uid 的成员判成「我自己」——
     * 一整屏成员都会变成看自己。
     */
    @Test
    fun `uid 都为空时不算自己`() {
        assertNotEquals(MemberProfile.RELATION_SELF, MemberProfile.relationOf("", "", null))
    }

    // —— 种子 ——

    /**
     * **本文件的核心**：资料卡是全局的，`nickname` 必须是全局昵称。
     * 拿 `GroupMember.displayName` 当昵称的话，任何人改一下自己的群昵称，
     * 别人打开他的资料页看到的「昵称」就跟着变了。
     */
    @Test
    fun `种子用全局昵称，群昵称不许泄进去`() {
        val card = MemberProfile.seedOf(member, null)
        assertEquals("用户1002", card.nickname)
        assertNotEquals("群里叫我小明", card.nickname)
        assertEquals("libeyond", card.username)
        assertEquals("/avatars/1002.jpg", card.avatarUrl)
    }

    /** 有备注就当种子，省掉「先显昵称、拉到名片再跳成备注」那一下闪动。 */
    @Test
    fun `好友的备注进种子`() {
        val card = MemberProfile.seedOf(member, friend)
        assertEquals("张曼玉1002-朝辞白帝彩云间", card.remark)
        // displayName 的回退链是 备注 → 昵称 → @句柄
        assertEquals("张曼玉1002-朝辞白帝彩云间", card.displayName)
    }

    @Test
    fun `陌生人没有备注，显示名回落到昵称`() {
        val card = MemberProfile.seedOf(member, null)
        assertEquals("", card.remark)
        assertEquals("用户1002", card.displayName)
    }

    /**
     * 群成员行里根本没有 phone/tags/presence，**不许编**——
     * 编出来的空值会盖掉名片接口随后拉回的真值吗？不会，但会让人以为「已经拉过了」。
     * 这里钉的是「种子只放本地确定的东西」。
     */
    @Test
    fun `种子不编造群成员行里没有的字段`() {
        val card = MemberProfile.seedOf(member, friend)
        assertEquals("", card.phone)
        assertEquals(emptyList<String>(), card.tags)
        assertEquals("", card.presence)
    }
}
