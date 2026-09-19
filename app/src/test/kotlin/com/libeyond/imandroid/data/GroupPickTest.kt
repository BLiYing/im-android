package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选人页三种用途的名单判据（2026-09-16 从 `GroupInfoHost` 的 @Composable 分支里抽出来）。
 *
 * 三条过滤写反了都**不会报错**，只会在点下去之后被服务端拒（300006 / 无意义的自转让 /
 * 邀请已在群的人必然失败），而用户看不出为什么——所以它们值得被钉住。
 */
class GroupPickTest {

    private fun member(uid: String, role: String) =
        GroupMember(userId = uid, username = uid, nickname = uid, role = role)

    private fun friend(uid: String) = FriendEntry(userId = uid, username = uid, nickname = uid)

    private val members = listOf(
        member("owner1", GroupMember.ROLE_OWNER),
        member("admin1", GroupMember.ROLE_ADMIN),
        member("m1", GroupMember.ROLE_MEMBER),
        member("m2", GroupMember.ROLE_MEMBER),
    )

    @Test
    fun add_admin_lists_plain_members_only() {
        val ids = GroupPick.candidates(PickPurpose.AddAdmin, members, emptyList(), "owner1").map { it.id }
        assertEquals(listOf("m1", "m2"), ids)
    }

    @Test
    fun transfer_excludes_myself() {
        val ids = GroupPick.candidates(PickPurpose.Transfer, members, emptyList(), "owner1").map { it.id }
        assertEquals(listOf("admin1", "m1", "m2"), ids)
        assertFalse("owner1" in ids)
    }

    @Test
    fun invite_excludes_friends_already_in_group() {
        val friends = listOf(friend("m1"), friend("f9"), friend("owner1"))
        val ids = GroupPick.candidates(PickPurpose.Invite, members, friends, "owner1").map { it.id }
        assertEquals(listOf("f9"), ids)
    }

    /** 群里只有我一个人时，转让页是空的——空态文案要对得上，不能落到通用的「没有可选的人」。 */
    @Test
    fun transfer_alone_is_empty() {
        val solo = listOf(member("owner1", GroupMember.ROLE_OWNER))
        assertTrue(GroupPick.candidates(PickPurpose.Transfer, solo, emptyList(), "owner1").isEmpty())
        assertEquals("群里还没有别人", GroupPick.emptyText(PickPurpose.Transfer))
    }

    @Test
    fun candidate_carries_display_name_and_handle() {
        val c = GroupPick.candidates(PickPurpose.AddAdmin, members, emptyList(), "owner1").first()
        assertEquals("m1", c.name)
        assertEquals("@m1", c.handle)
    }

    @Test
    fun only_invite_is_multi_select() {
        assertTrue(GroupPick.isMultiSelect(PickPurpose.Invite))
        assertFalse(GroupPick.isMultiSelect(PickPurpose.AddAdmin))
        assertFalse(GroupPick.isMultiSelect(PickPurpose.Transfer))
    }

    @Test
    fun titles_match_each_purpose() {
        assertEquals("添加管理员", GroupPick.title(PickPurpose.AddAdmin))
        assertEquals("选择新群主", GroupPick.title(PickPurpose.Transfer))
        assertEquals("邀请入群", GroupPick.title(PickPurpose.Invite))
    }

    @Test
    fun call_lists_everyone_but_me_regardless_of_role() {
        val ids = GroupPick.candidates(PickPurpose.Call, members, emptyList(), "owner1").map { it.id }
        assertEquals(listOf("admin1", "m1", "m2"), ids)
    }

    @Test
    fun call_is_multi_select_and_caps_at_eight() {
        assertTrue(GroupPick.isMultiSelect(PickPurpose.Call))
        var picked = emptySet<String>()
        for (i in 1..GroupPick.MAX_CALL_PICK) picked = GroupPick.toggle(PickPurpose.Call, picked, "u$i")
        assertEquals(GroupPick.MAX_CALL_PICK, picked.size)
        // 满了再加：原样返回（调用方靠「没变」判断要提示）
        assertEquals(picked, GroupPick.toggle(PickPurpose.Call, picked, "u9"))
        // 已选的永远能取消
        assertFalse("u1" in GroupPick.toggle(PickPurpose.Call, picked, "u1"))
    }

    @Test
    fun invite_has_no_cap() {
        var picked = emptySet<String>()
        for (i in 1..20) picked = GroupPick.toggle(PickPurpose.Invite, picked, "u$i")
        assertEquals(20, picked.size)
    }
}
